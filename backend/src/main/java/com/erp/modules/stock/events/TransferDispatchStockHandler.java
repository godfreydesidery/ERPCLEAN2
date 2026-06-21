package com.erp.modules.stock.events;

import com.erp.modules.stock.domain.dto.TransferDispatchedPayload;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.StockPostingService;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code STOCK.TRANSFER.DISPATCHED} — posts TRANSFER_OUT at the source location
 * and TRANSFER_IN at the in-transit pseudo-location (ADR-0028 D-5, D-12).
 *
 * <p>Value-preserving: TRANSFER_OUT and TRANSFER_IN are net-zero on account 1300 — no GL posted.
 */
@Component
public class TransferDispatchStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TransferDispatchStockHandler.class);

    static final String CONSUMER = "STOCK.TRANSFER_DISPATCH";

    private final IdempotencyGuard         guard;
    private final StockPostingService      posting;
    private final InventoryValuationService valuation;
    private final ObjectMapper             objectMapper;

    public TransferDispatchStockHandler(IdempotencyGuard guard,
                                         StockPostingService posting,
                                         InventoryValuationService valuation,
                                         ObjectMapper objectMapper) {
        this.guard        = guard;
        this.posting      = posting;
        this.valuation    = valuation;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.STOCK_TRANSFER_DISPATCHED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("TransferDispatchStockHandler: event uid={} already processed — skipping",
                    event.getUid());
            return;
        }

        TransferDispatchedPayload payload = deserialise(event.getPayload());

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            for (TransferDispatchedPayload.LineItem line : payload.lines()) {

                // (1) TRANSFER_OUT at source location (quantity decreases).
                // Suffix ":OUT" distinguishes this leg from (2) for the (sourceEventUid, productId)
                // idempotency backstop in StockPostingServiceImpl — both legs share the same event
                // uid so without the suffix the second leg is skipped as a duplicate (STOCK-039).
                posting.post(
                        payload.companyId(), payload.sourceBranchId(), payload.sourceLocationId(),
                        line.productId(), line.qtyInBase().negate(),
                        MovementType.TRANSFER_OUT,
                        event.getUid() + ":OUT", "STOCK_TRANSFER", payload.transferUid(),
                        null, null, payload.dispatchedAt(),
                        null,
                        line.unitCostAmount(), line.valueAmount() != null ? line.valueAmount().negate() : null);

                // (2) TRANSFER_IN at in-transit pseudo-location (quantity increases).
                // Suffix ":IN" makes the idempotency key unique from the OUT leg above.
                posting.post(
                        payload.companyId(), payload.sourceBranchId(), payload.inTransitLocationId(),
                        line.productId(), line.qtyInBase(),
                        MovementType.TRANSFER_IN,
                        event.getUid() + ":IN", "STOCK_TRANSFER", payload.transferUid(),
                        null, null, payload.dispatchedAt(),
                        null,
                        line.unitCostAmount(), line.valueAmount());

                // (3) Move on_hand_value from source → in-transit so valuation stays correct (issue #12).
                valuation.transferCost(
                        payload.companyId(),
                        payload.sourceBranchId(), payload.sourceLocationId(),
                        payload.sourceBranchId(), payload.inTransitLocationId(),
                        line.productId(), line.qtyInBase());
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

    private TransferDispatchedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, TransferDispatchedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise TransferDispatchedPayload: " + ex.getMessage(), ex);
        }
    }
}
