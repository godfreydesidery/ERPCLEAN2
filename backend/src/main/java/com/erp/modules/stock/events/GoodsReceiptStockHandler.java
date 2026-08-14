package com.erp.modules.stock.events;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.service.InventoryGlPoster;
import com.erp.modules.stock.service.InventoryGlPoster.ReceiptLeg;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.StockBatchService;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.modules.stock.service.StockSerialService;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code STOCK.RECEIVED} — adds stock on Goods Receipt from Purchases (ADR-0010 D-5).
 *
 * <p>ADR-0020: after the +quantity movement, recomputes the moving-average cost and posts
 * DR INVENTORY (1300) / CR GRNI (2150) via {@link InventoryGlPoster#postReceiptInNewTx}
 * (REQUIRES_NEW — a GL anomaly never poisons the dispatch TX).
 *
 * <p>V76: after the quantity movement, if the payload line carries lot/serial data, delegates to
 * {@link StockBatchService#receiveQty} and/or {@link StockSerialService#record} using the branch
 * default stock location. If no default location exists the batch/serial writes are skipped with a
 * WARN — the receipt itself is never failed for missing tracking data (soft validation).
 */
@Component
public class GoodsReceiptStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(GoodsReceiptStockHandler.class);

    static final String CONSUMER = "STOCK.GOODS_RECEIPT";
    private static final String DOC_TYPE = "GOODS_RECEIPT";

    private final IdempotencyGuard           guard;
    private final StockPostingService        posting;
    private final ProductService             productService;
    private final InventoryValuationService  valuation;
    private final InventoryGlPoster          glPoster;
    private final StockBatchService          batchService;
    private final StockSerialService         serialService;
    private final StockLocationRepository    locationRepo;
    private final ObjectMapper               objectMapper;

    public GoodsReceiptStockHandler(IdempotencyGuard guard,
                                     StockPostingService posting,
                                     ProductService productService,
                                     InventoryValuationService valuation,
                                     InventoryGlPoster glPoster,
                                     StockBatchService batchService,
                                     StockSerialService serialService,
                                     StockLocationRepository locationRepo,
                                     ObjectMapper objectMapper) {
        this.guard          = guard;
        this.posting        = posting;
        this.productService = productService;
        this.valuation      = valuation;
        this.glPoster       = glPoster;
        this.batchService   = batchService;
        this.serialService  = serialService;
        this.locationRepo   = locationRepo;
        this.objectMapper   = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.STOCK_RECEIVED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("GoodsReceiptStockHandler: event uid={} already processed — skipping", event.getUid());
            return;
        }

        StockReceivedPayload payload = deserialise(event.getPayload());

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(RequestContext.Principal.system(event.getCompanyId(), event.getBranchId()));
        try {
            // Resolve the branch default stock location once — used for batch/serial writes (V76).
            // If none exists the tracking writes are skipped (soft), but qty/GL proceed normally.
            Long defaultLocationId = resolveDefaultLocationId(
                    event.getCompanyId(), event.getBranchId(), payload.receiptUid());

            List<ReceiptLeg> glLegs = new ArrayList<>();

            // One idempotency key per LINE, not per event. A GRN routinely receives the same product
            // in two lots (different expiry, different lot number) — with a single event-wide key the
            // second lot looked like a redelivery of the first, so its quantity never landed while
            // the moving-average recompute and the DR INVENTORY leg counted both.
            MovementSourceKeys keys = MovementSourceKeys.forEvent(event.getUid());

            for (StockReceivedPayload.LineItem line : payload.lines()) {
                ProductDto product = productService.getByUid(line.productUid());
                if (!product.stockable()) {
                    log.info("GoodsReceiptStockHandler: skipping non-stockable product uid={} " +
                                    "name='{}' on receipt uid={} (D-3, defensive)",
                            line.productUid(), product.name(), payload.receiptUid());
                    continue;
                }

                String sourceKey = keys.nextFor(line.productId());

                // Probe before the valuation recompute: recomputeOnReceipt mutates avg_cost and
                // on_hand_value immediately, while post() would no-op on an already-applied key —
                // value would move without quantity. Skip the line whole, tracking writes included.
                if (posting.alreadyPosted(sourceKey, line.productId())) {
                    log.debug("GoodsReceiptStockHandler: line for product uid={} on receipt uid={} " +
                                    "already posted for this event — skipping (idempotent redelivery)",
                            line.productUid(), payload.receiptUid());
                    continue;
                }

                // (1) Recompute moving-average cost (ADR-0020 D-2); returns receipt value for GL leg.
                BigDecimal receiptCost = line.unitCostAmount();
                if (receiptCost == null) {
                    log.warn("GoodsReceiptStockHandler: unitCostAmount is null for product uid={} " +
                                     "on receipt uid={} — treating as zero-cost (D-3 backward note)",
                            line.productUid(), payload.receiptUid());
                    receiptCost = BigDecimal.ZERO;
                }
                BigDecimal receiptValue = valuation.recomputeOnReceipt(
                        event.getCompanyId(), event.getBranchId(), line.productId(),
                        line.qtyInBase(), receiptCost);

                // (2) Post the quantity movement with cost recorded on the row (D-2).
                posting.post(
                        event.getCompanyId(), event.getBranchId(), line.productId(),
                        line.qtyInBase(),
                        MovementType.GOODS_RECEIPT,
                        sourceKey,
                        DOC_TYPE, payload.receiptUid(),
                        null, null,
                        payload.receivedAt(),
                        null,
                        receiptCost, receiptValue);

                // (3) Accumulate GL legs for the journal (one journal per receipt, D-4a).
                if (receiptValue != null && receiptValue.compareTo(BigDecimal.ZERO) > 0) {
                    // FOLLOW-001: memo uses product code (fallback to NAME, never a uid) + GRN number.
                    glLegs.add(new ReceiptLeg(
                            line.productUid(),   // retained for traceability; not rendered in the memo
                            product.code() != null ? product.code() : product.name(),
                            receiptValue));
                }

                // (4) V76: batch/serial tracking — soft, never fails the dispatch.
                if (defaultLocationId != null) {
                    writeBatchTracking(event, line, product, defaultLocationId);
                    writeSerialTracking(event, line, product, defaultLocationId, payload.receiptUid());
                }
            }

            // (5) Post one GL journal DR INVENTORY / CR GRNI for the whole receipt (D-4a).
            if (!glLegs.isEmpty()) {
                LocalDate postingDate = payload.receivedAt() != null
                        ? payload.receivedAt().atZone(ZoneOffset.UTC).toLocalDate()
                        : LocalDate.now();
                String glEntryUid = glPoster.postReceiptInNewTx(
                        event.getCompanyId(), event.getBranchId(), postingDate,
                        payload.receiptUid(), payload.receiptNumber(), "TZS", glLegs);
                if (glEntryUid == null) {
                    log.warn("GoodsReceiptStockHandler: GL post returned null for receipt uid={} " +
                                     "— GL not configured or period closed (anomaly, qty still posted)",
                            payload.receiptUid());
                }
            }

        } finally {
            if (previous == null) {
                RequestContext.clear();
            } else {
                RequestContext.set(previous);
            }
        }

        guard.markProcessed(CONSUMER, event.getUid());
    }

    // -------------------------------------------------------------------------
    // V76 helpers — batch and serial tracking (all soft: exceptions are caught + warned)
    // -------------------------------------------------------------------------

    /**
     * Writes a stock_batches row when the product is lot-tracked OR the payload carries a lotNumber
     * or expiryDate. If lotNumber is null/blank, uses "UNTRACKED" as a sentinel so the batch row
     * still captures the expiry date for FEFO — but only when expiryDate is also present; otherwise
     * skips silently.
     */
    private void writeBatchTracking(DomainEvent event, StockReceivedPayload.LineItem line,
                                     ProductDto product, Long locationId) {
        boolean haslotData = (line.lotNumber() != null && !line.lotNumber().isBlank())
                || line.expiryDate() != null;
        boolean shouldWrite = product.lotTracked() || haslotData;
        if (!shouldWrite) {
            return;
        }
        String lotNumber = (line.lotNumber() != null && !line.lotNumber().isBlank())
                ? line.lotNumber().trim()
                : "UNTRACKED";
        try {
            batchService.receiveQty(
                    event.getCompanyId(), event.getBranchId(), locationId, line.productId(),
                    lotNumber, line.manufactureDate(), line.expiryDate(),
                    line.qtyInBase(), null /* actorId — SYSTEM */);
            log.debug("GoodsReceiptStockHandler: batch recorded lot='{}' product={} qty={} receipt={}",
                    lotNumber, line.productId(), line.qtyInBase(), event.getUid());
        } catch (Exception ex) {
            log.warn("GoodsReceiptStockHandler: batch write failed for lot='{}' product={} receipt={} — " +
                             "skipping (soft); cause: {}",
                    lotNumber, line.productId(), event.getUid(), ex.getMessage());
        }
    }

    /**
     * Records stock_serials rows for each serial in the payload when the product is serial-tracked
     * OR the payload carries serial numbers. Idempotent per (company, product, serial).
     */
    private void writeSerialTracking(DomainEvent event, StockReceivedPayload.LineItem line,
                                      ProductDto product, Long locationId, String receiptUid) {
        List<String> serials = line.serialNumbers();
        boolean hasSerials = serials != null && !serials.isEmpty();
        if (!product.serialTracked() && !hasSerials) {
            return;
        }
        if (!hasSerials) {
            return; // serial-tracked product but no serials provided — soft skip
        }
        for (String serial : serials) {
            if (serial == null || serial.isBlank()) continue;
            try {
                serialService.record(
                        event.getCompanyId(), event.getBranchId(), locationId, line.productId(),
                        serial.trim(), receiptUid, null /* actorId — SYSTEM */);
                log.debug("GoodsReceiptStockHandler: serial recorded serial='{}' product={} receipt={}",
                        serial, line.productId(), event.getUid());
            } catch (Exception ex) {
                log.warn("GoodsReceiptStockHandler: serial write failed serial='{}' product={} receipt={} — " +
                                 "skipping (soft); cause: {}",
                        serial, line.productId(), event.getUid(), ex.getMessage());
            }
        }
    }

    /**
     * Resolve the branch default stock location id.
     * Returns null (with a WARN) if none exists — callers treat null as "skip tracking writes".
     */
    private Long resolveDefaultLocationId(Long companyId, Long branchId, String receiptUid) {
        return locationRepo.findByCompanyIdAndBranchIdAndIsDefaultTrue(companyId, branchId)
                .map(StockLocation::getId)
                .orElseGet(() -> {
                    log.warn("GoodsReceiptStockHandler: no default stock location for company={} branch={} " +
                                     "receipt={} — batch/serial tracking writes skipped (V76 soft)",
                            companyId, branchId, receiptUid);
                    return null;
                });
    }

    private StockReceivedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, StockReceivedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot deserialise StockReceivedPayload: " + ex.getMessage(), ex);
        }
    }
}
