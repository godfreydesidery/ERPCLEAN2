package com.erp.modules.stock.events;

import com.erp.modules.stock.domain.dto.StockReceiptVoidedPayload;
import com.erp.modules.stock.domain.entity.StockMovement;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>Reverse-from-ledger (OQ-STOCK-10): looks up {@code stock_movements} by
 * {@code source_document_uid = receiptUid AND movement_type = GOODS_RECEIPT} and posts a
 * GOODS_RECEIPT_REVERSAL (−) for each. Correctly handles:
 * <ul>
 *   <li>Partial receipts (each posts its own event; reversal reverses exactly what was posted).</li>
 *   <li>Non-stockable defensive skips (a skipped line has no GOODS_RECEIPT row — nothing to reverse).</li>
 *   <li>Out-of-order void: if no GOODS_RECEIPT rows exist → anomaly log, no phantom movement.</li>
 * </ul>
 *
 * <p>Dedupes on the <em>void</em> event's own uid (distinct from the original receipt event's uid),
 * so a redelivered void cannot double-reverse.
 */
@Component
public class GoodsReceiptReversalStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(GoodsReceiptReversalStockHandler.class);

    static final String CONSUMER = "STOCK.GOODS_RECEIPT_REVERSAL";
    private static final String DOC_TYPE = "GOODS_RECEIPT";

    private final IdempotencyGuard guard;
    private final StockPostingService posting;
    private final StockMovementRepository movementRepository;
    private final ObjectMapper objectMapper;

    public GoodsReceiptReversalStockHandler(IdempotencyGuard guard,
                                             StockPostingService posting,
                                             StockMovementRepository movementRepository,
                                             ObjectMapper objectMapper) {
        this.guard              = guard;
        this.posting            = posting;
        this.movementRepository = movementRepository;
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

        // Look up what was actually received for this receipt (reverse-from-ledger, OQ-STOCK-10).
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
            for (StockMovement original : received) {
                // Post opposite-sign GOODS_RECEIPT_REVERSAL.
                // source_event_uid = the VOID event's uid (dedupes on the void, not the original).
                posting.post(
                        original.getCompanyId(), original.getBranchId(), original.getProductId(),
                        original.getQuantity().negate(),  // GOODS_RECEIPT was +; reversal is −
                        MovementType.GOODS_RECEIPT_REVERSAL,
                        event.getUid(),                   // void event uid (D-5)
                        DOC_TYPE, payload.receiptUid(),
                        null, null,
                        null,                             // occurredAt defaults to now()
                        null);
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
