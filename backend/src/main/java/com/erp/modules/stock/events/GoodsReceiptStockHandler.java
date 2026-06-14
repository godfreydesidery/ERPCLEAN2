package com.erp.modules.stock.events;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.service.InventoryGlPoster;
import com.erp.modules.stock.service.InventoryGlPoster.ReceiptLeg;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.StockPostingService;
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
    private final ObjectMapper               objectMapper;

    public GoodsReceiptStockHandler(IdempotencyGuard guard,
                                     StockPostingService posting,
                                     ProductService productService,
                                     InventoryValuationService valuation,
                                     InventoryGlPoster glPoster,
                                     ObjectMapper objectMapper) {
        this.guard          = guard;
        this.posting        = posting;
        this.productService = productService;
        this.valuation      = valuation;
        this.glPoster       = glPoster;
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
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            List<ReceiptLeg> glLegs = new ArrayList<>();

            for (StockReceivedPayload.LineItem line : payload.lines()) {
                ProductDto product = productService.getByUid(line.productUid());
                if (!product.stockable()) {
                    log.info("GoodsReceiptStockHandler: skipping non-stockable product uid={} " +
                                    "name='{}' on receipt uid={} (D-3, defensive)",
                            line.productUid(), product.name(), payload.receiptUid());
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
                        event.getUid(),
                        DOC_TYPE, payload.receiptUid(),
                        null, null,
                        payload.receivedAt(),
                        null,
                        receiptCost, receiptValue);

                // (3) Accumulate GL legs for the journal (one journal per receipt, D-4a).
                if (receiptValue != null && receiptValue.compareTo(BigDecimal.ZERO) > 0) {
                    // Use line uid as memo key; fall back to productUid if grLineUid not in payload
                    glLegs.add(new ReceiptLeg(
                            line.productUid(),   // grLineUid best-effort; Purchases may enrich later
                            product.code() != null ? product.code() : line.productUid(),
                            receiptValue));
                }
            }

            // (4) Post one GL journal DR INVENTORY / CR GRNI for the whole receipt (D-4a).
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

    private StockReceivedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, StockReceivedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot deserialise StockReceivedPayload: " + ex.getMessage(), ex);
        }
    }
}
