package com.erp.modules.stock.events;

import com.erp.modules.stock.domain.dto.StockReceiptVoidedPayload;
import com.erp.modules.stock.domain.entity.StockMovement;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.service.InventoryGlPoster;
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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code STOCK.RECEIPT.VOIDED} — reverses GOODS_RECEIPT movements from the ledger
 * (ADR-0010 D-5, the buying-side mirror of {@link SaleReversalStockHandler}).
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
    private final ObjectMapper               objectMapper;

    public GoodsReceiptReversalStockHandler(IdempotencyGuard guard,
                                             StockPostingService posting,
                                             StockMovementRepository movementRepository,
                                             InventoryValuationService valuation,
                                             InventoryGlPoster glPoster,
                                             ObjectMapper objectMapper) {
        this.guard              = guard;
        this.posting            = posting;
        this.movementRepository = movementRepository;
        this.valuation          = valuation;
        this.glPoster           = glPoster;
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
}
