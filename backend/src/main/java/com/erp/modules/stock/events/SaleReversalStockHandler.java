package com.erp.modules.stock.events;

import com.erp.modules.sales.domain.dto.SaleVoidedPayload;
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
 * Consumes {@code SALE.VOIDED} — reverses SALE_ISSUE movements by reading Stock's own ledger
 * (ADR-0010 D-5, OQ-STOCK-10).
 *
 * <p>The reversal source is {@code stock_movements} filtered by
 * {@code source_document_uid = invoiceUid AND movement_type = SALE_ISSUE} — not the void payload's
 * lines. This is robust to recipe explosion (the ledger contains component-level rows, not composed
 * product rows) and to non-stockable skips (lines that were never issued have nothing to reverse).
 *
 * <p>Out-of-order anomaly (OQ-STOCK-10): if no SALE_ISSUE rows exist for the invoice, a log/metric
 * is recorded; no phantom movement is posted (zero-effect movements are forbidden by the DB CHECK).
 * The {@code processed_events} marker is still written so the void is not re-attempted.
 */
@Component
public class SaleReversalStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SaleReversalStockHandler.class);

    static final String CONSUMER = "STOCK.SALE_REVERSAL";
    private static final String DOC_TYPE = "SALES_INVOICE";

    private final IdempotencyGuard guard;
    private final StockPostingService posting;
    private final StockMovementRepository movementRepository;
    private final ObjectMapper objectMapper;

    public SaleReversalStockHandler(IdempotencyGuard guard,
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
        return DomainEventType.SALE_VOIDED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("SaleReversalStockHandler: event uid={} already processed — skipping", event.getUid());
            return;
        }

        SaleVoidedPayload payload = deserialise(event.getPayload());

        // Look up what was actually issued for this invoice (reverse-from-ledger, OQ-STOCK-10).
        List<StockMovement> issued = movementRepository
                .findBySourceDocumentUidAndMovementType(payload.invoiceUid(), MovementType.SALE_ISSUE);

        if (issued.isEmpty()) {
            // Out-of-order or entirely non-stockable invoice — record anomaly, no phantom movement.
            log.warn("SaleReversalStockHandler: SALE.VOIDED for invoice uid={} but no SALE_ISSUE " +
                            "movements found in ledger — anomaly recorded (OQ-STOCK-10). " +
                            "void event uid={}",
                    payload.invoiceUid(), event.getUid());
            // Still mark processed so this void is not re-attempted.
            guard.markProcessed(CONSUMER, event.getUid());
            return;
        }

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            for (StockMovement original : issued) {
                // Post opposite-sign SALE_REVERSAL — same product, same magnitude, sign flipped.
                posting.post(
                        original.getCompanyId(), original.getBranchId(), original.getProductId(),
                        original.getQuantity().negate(),  // negate: SALE_ISSUE was negative → reversal is positive
                        MovementType.SALE_REVERSAL,
                        event.getUid(),                   // source_event_uid = the VOID event's uid
                        DOC_TYPE, payload.invoiceUid(),
                        null, null,
                        null,                             // occurredAt → now() (StockMovement default)
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

    private SaleVoidedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, SaleVoidedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot deserialise SaleVoidedPayload: " + ex.getMessage(), ex);
        }
    }
}
