package com.erp.modules.stock.events;

import com.erp.modules.stock.domain.dto.StockReceiptVoidedPayload;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockMovement;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.service.InventoryGlPoster;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code STOCK.RECEIPT.VOIDED} — reverses GOODS_RECEIPT movements from the ledger
 * (ADR-0010 D-5, the buying-side mirror of {@link SaleReversalStockHandler}).
 *
 * <p>D-2 — batch/serial reversal: after the qty movement for each line is posted, backs out
 * {@code stock_batches}/{@code stock_serials} rows written on receipt (symmetric with
 * {@link GoodsReceiptStockHandler}). Batch/serial writes are looked up from the payload lines
 * (matched to the ledger movement by {@code productId}, best-effort in the rare case of two GR
 * lines for the same product) and are entirely soft: a lookup miss, a moved-on serial, or any
 * write exception is logged at WARN and skipped — the qty/GL reversal above is never poisoned by a
 * tracking hiccup.
 *
 * <p>FIX A: the reversal QUANTITY is sourced from the same payload line that supplies the LOT
 * (never from the ledger movement's own quantity) — the movement finder has no {@code ORDER BY},
 * so for two GR lines of the same product in different lots, pairing a movement's quantity with a
 * best-effort-matched line's lot could decrement the wrong lot by the wrong amount.
 *
 * <p>FIX B: batch reversal fires when the payload line carries lot/expiry data OR the line's
 * {@code lotTracked} flag is set (mirrors {@link GoodsReceiptStockHandler#writeBatchTracking}'s
 * {@code shouldWrite}) — so a lot-tracked product received with a blank lot (the forward path's
 * {@code "UNTRACKED"} sentinel) is correctly reversed instead of silently skipped.
 *
 * <p>FIX C: batch reversal targets the iterated {@link StockMovement}'s OWN receipt-time
 * {@code locationId}, not a location re-resolved at void time — a branch default-location change
 * between receipt and void must not cause the reversal to miss the batch row.
 *
 * <p>ADR-0020: for each original GOODS_RECEIPT movement, reads the stored {@code value_amount}
 * (cost at time of receipt) and calls {@link InventoryValuationService#reverseReceipt} to back
 * out the moving-average. Posts DR GRNI (2150) / CR INVENTORY (1300) via
 * {@link InventoryGlPoster#postReceiptReversalInNewTx} (REQUIRES_NEW — GL anomaly never poisons
 * the dispatch TX). If the original movement had no cost (null value_amount), the GL leg is
 * skipped with a WARN; quantity reversal still proceeds.
 */
@Component
public class GoodsReceiptReversalStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(GoodsReceiptReversalStockHandler.class);

    static final String CONSUMER = "STOCK.GOODS_RECEIPT_REVERSAL";
    private static final String DOC_TYPE = "GOODS_RECEIPT";

    private final IdempotencyGuard           guard;
    private final StockPostingService        posting;
    private final StockMovementRepository    movementRepository;
    private final InventoryValuationService  valuation;
    private final InventoryGlPoster          glPoster;
    private final StockBatchService          batchService;
    private final StockSerialService         serialService;
    private final StockLocationRepository    locationRepo;
    private final ObjectMapper               objectMapper;

    public GoodsReceiptReversalStockHandler(IdempotencyGuard guard,
                                             StockPostingService posting,
                                             StockMovementRepository movementRepository,
                                             InventoryValuationService valuation,
                                             InventoryGlPoster glPoster,
                                             StockBatchService batchService,
                                             StockSerialService serialService,
                                             StockLocationRepository locationRepo,
                                             ObjectMapper objectMapper) {
        this.guard              = guard;
        this.posting            = posting;
        this.movementRepository = movementRepository;
        this.valuation          = valuation;
        this.glPoster           = glPoster;
        this.batchService       = batchService;
        this.serialService      = serialService;
        this.locationRepo       = locationRepo;
        this.objectMapper       = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.STOCK_RECEIPT_VOIDED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("GoodsReceiptReversalStockHandler: event uid={} already processed — skipping",
                    event.getUid());
            return;
        }

        StockReceiptVoidedPayload payload = deserialise(event.getPayload());

        List<StockMovement> received = movementRepository
                .findBySourceDocumentUidAndMovementType(payload.receiptUid(), MovementType.GOODS_RECEIPT);

        if (received.isEmpty()) {
            log.warn("GoodsReceiptReversalStockHandler: STOCK.RECEIPT.VOIDED for receipt uid={} but no " +
                            "GOODS_RECEIPT movements found in ledger — anomaly recorded (OQ-STOCK-10). " +
                            "void event uid={}",
                    payload.receiptUid(), event.getUid());
            guard.markProcessed(CONSUMER, event.getUid());
            return;
        }

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            BigDecimal totalOriginalValue = BigDecimal.ZERO;
            boolean anyCostNull = false;

            // D-2: resolve the branch default location once — used for batch/serial reversal
            // writes, mirroring GoodsReceiptStockHandler's resolveDefaultLocationId. Null (with a
            // WARN) if none exists; tracking reversal is then skipped (soft), qty/GL proceed.
            Long defaultLocationId = resolveDefaultLocationId(
                    event.getCompanyId(), event.getBranchId(), payload.receiptUid());
            Map<Long, Deque<StockReceiptVoidedPayload.LineItem>> linesByProduct =
                    groupLinesByProduct(payload.lines());

            for (StockMovement original : received) {
                BigDecimal originalValue = original.getValueAmount();
                // Original GOODS_RECEIPT quantity is positive; reversal posts negative.
                BigDecimal originalQty = original.getQuantity();

                if (originalValue == null) {
                    log.warn("GoodsReceiptReversalStockHandler: original GOODS_RECEIPT movement uid={} " +
                                     "has no value_amount — avg recompute skipped for this row (D-2 edge)",
                            original.getUid());
                    anyCostNull = true;
                } else {
                    // Back out the receipt from the moving-average (ADR-0020 D-5).
                    valuation.reverseReceipt(
                            original.getCompanyId(), original.getBranchId(), original.getProductId(),
                            originalQty.abs(), originalValue);
                    totalOriginalValue = totalOriginalValue.add(originalValue);
                }

                // Post opposite-sign GOODS_RECEIPT_REVERSAL quantity movement.
                posting.post(
                        original.getCompanyId(), original.getBranchId(), original.getProductId(),
                        originalQty.negate(),             // GOODS_RECEIPT was +; reversal is −
                        MovementType.GOODS_RECEIPT_REVERSAL,
                        event.getUid(),                   // void event uid (D-5)
                        DOC_TYPE, payload.receiptUid(),
                        null, null,
                        null,
                        null,
                        original.getUnitCostAmount(),
                        originalValue);

                // D-2: batch/serial reversal — soft, never fails the dispatch.
                // FIX C: prefer the movement's OWN receipt-time location; fall back to the
                // re-resolved branch default only if the movement itself carries no location.
                Long batchLocationId = original.getLocationId() != null
                        ? original.getLocationId() : defaultLocationId;
                StockReceiptVoidedPayload.LineItem matchedLine =
                        pollLineForProduct(linesByProduct, original.getProductId());
                if (matchedLine != null) {
                    // FIX A: qty comes from the SAME line that supplies the lot — never from the
                    // ledger's originalQty (see class Javadoc).
                    if (batchLocationId != null) {
                        reverseBatchTracking(event, matchedLine, batchLocationId, matchedLine.qtyInBase());
                    }
                    reverseSerialTracking(event, matchedLine, payload.receiptUid());
                }
            }

            // Post one GL journal DR GRNI / CR INVENTORY for the entire receipt reversal (D-5).
            if (totalOriginalValue.compareTo(BigDecimal.ZERO) > 0) {
                String glEntryUid = glPoster.postReceiptReversalInNewTx(
                        event.getCompanyId(), event.getBranchId(), LocalDate.now(),
                        payload.receiptUid(), payload.receiptNumber(), "TZS", totalOriginalValue);
                if (glEntryUid == null) {
                    log.warn("GoodsReceiptReversalStockHandler: receipt reversal GL post returned null " +
                                     "for receipt uid={} — GL not configured (qty still reversed)",
                            payload.receiptUid());
                }
            } else if (anyCostNull) {
                log.warn("GoodsReceiptReversalStockHandler: all original GOODS_RECEIPT movements for " +
                                 "receipt uid={} lacked value_amount — no GRNI reversal GL posted",
                        payload.receiptUid());
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

    private StockReceiptVoidedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, StockReceiptVoidedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot deserialise StockReceiptVoidedPayload: " + ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // D-2 helpers — batch and serial reversal (all soft: exceptions are caught + warned)
    // -------------------------------------------------------------------------

    /** Groups payload lines by productId, preserving declaration order (best-effort correlation
     * with ledger movements for the rare case of two GR lines on the same product). */
    private Map<Long, Deque<StockReceiptVoidedPayload.LineItem>> groupLinesByProduct(
            List<StockReceiptVoidedPayload.LineItem> lines) {
        Map<Long, Deque<StockReceiptVoidedPayload.LineItem>> byProduct = new HashMap<>();
        if (lines == null) {
            return byProduct;
        }
        for (StockReceiptVoidedPayload.LineItem line : lines) {
            byProduct.computeIfAbsent(line.productId(), k -> new ArrayDeque<>()).add(line);
        }
        return byProduct;
    }

    /** Pops the next unmatched payload line for a product id, or null if none remain. */
    private StockReceiptVoidedPayload.LineItem pollLineForProduct(
            Map<Long, Deque<StockReceiptVoidedPayload.LineItem>> byProduct, Long productId) {
        Deque<StockReceiptVoidedPayload.LineItem> queue = byProduct.get(productId);
        return queue != null ? queue.poll() : null;
    }

    /**
     * Reverses a {@code stock_batches} row when the product is lot-tracked OR the payload line
     * carries lot/expiry data — mirrors the forward {@code writeBatchTracking}'s {@code shouldWrite}
     * (FIX B), including its {@code "UNTRACKED"} sentinel when the lot number is blank.
     */
    private void reverseBatchTracking(DomainEvent event, StockReceiptVoidedPayload.LineItem line,
                                       Long locationId, BigDecimal qty) {
        boolean hasLotData = (line.lotNumber() != null && !line.lotNumber().isBlank())
                || line.expiryDate() != null;
        boolean shouldReverse = line.lotTracked() || hasLotData;
        if (!shouldReverse || qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        String lotNumber = (line.lotNumber() != null && !line.lotNumber().isBlank())
                ? line.lotNumber().trim()
                : "UNTRACKED";
        try {
            batchService.reverseReceiptQty(
                    event.getCompanyId(), event.getBranchId(), locationId, line.productId(),
                    lotNumber, qty, null /* actorId — SYSTEM */);
            log.debug("GoodsReceiptReversalStockHandler: batch reversed lot='{}' product={} qty=-{} " +
                            "receipt={}", lotNumber, line.productId(), qty, event.getUid());
        } catch (Exception ex) {
            log.warn("GoodsReceiptReversalStockHandler: batch reversal failed for lot='{}' product={} " +
                             "receipt={} — skipping (soft); cause: {}",
                    lotNumber, line.productId(), event.getUid(), ex.getMessage());
        }
    }

    /**
     * Removes each {@code stock_serials} row recorded by this exact receipt, IF it is still
     * IN_STOCK (never touches an ISSUED/RETURNED serial) — delegates the guard to
     * {@link StockSerialService#removeReceived}.
     */
    private void reverseSerialTracking(DomainEvent event, StockReceiptVoidedPayload.LineItem line,
                                        String receiptUid) {
        List<String> serials = line.serialNumbers();
        if (serials == null || serials.isEmpty()) {
            return;
        }
        for (String serial : serials) {
            if (serial == null || serial.isBlank()) continue;
            try {
                serialService.removeReceived(
                        event.getCompanyId(), line.productId(), serial.trim(), receiptUid);
                log.debug("GoodsReceiptReversalStockHandler: serial removal attempted serial='{}' " +
                                "product={} receipt={}", serial, line.productId(), event.getUid());
            } catch (Exception ex) {
                log.warn("GoodsReceiptReversalStockHandler: serial removal failed serial='{}' product={} " +
                                 "receipt={} — skipping (soft); cause: {}",
                        serial, line.productId(), event.getUid(), ex.getMessage());
            }
        }
    }

    /**
     * Resolve the branch default stock location id (mirrors
     * {@code GoodsReceiptStockHandler.resolveDefaultLocationId}).
     * Returns null (with a WARN) if none exists — callers treat null as "skip tracking reversal".
     */
    private Long resolveDefaultLocationId(Long companyId, Long branchId, String receiptUid) {
        return locationRepo.findByCompanyIdAndBranchIdAndIsDefaultTrue(companyId, branchId)
                .map(StockLocation::getId)
                .orElseGet(() -> {
                    log.warn("GoodsReceiptReversalStockHandler: no default stock location for company={} " +
                                     "branch={} receipt={} — batch/serial reversal skipped (D-2 soft)",
                            companyId, branchId, receiptUid);
                    return null;
                });
    }
}
