package com.erp.modules.stock.events;

import com.erp.modules.stock.domain.dto.TransferDispatchedPayload;
import com.erp.modules.stock.domain.dto.TransferReceivedPayload;
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
 * Consumes {@code STOCK.TRANSFER.RECEIVED} — posts TRANSFER_OUT at the in-transit pseudo-location
 * and TRANSFER_IN at the destination location (ADR-0028 D-5, D-12).
 *
 * <p>Value-preserving: these movements are net-zero on account 1300 — no GL posted.
 * The in-transit balance clears to zero after the receive pair.
 *
 * <p><strong>Per-leg, per-line idempotency key:</strong> mirrors {@link TransferDispatchStockHandler}
 * exactly — keys come from {@link MovementSourceKeys} with leg codes {@code 'R'} (transit OUT) and
 * {@code 'r'} (destination IN), each indexed by the product's occurrence within the transfer. Both
 * distinctions are load-bearing: without the leg code the destination TRANSFER_IN is suppressed by
 * the (source_event_uid, product_id) backstop in
 * {@link com.erp.modules.stock.service.StockPostingServiceImpl}; without the line index a product
 * listed on two lines has only its first line received.
 */
@Component
public class TransferReceiveStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(TransferReceiveStockHandler.class);

    static final String CONSUMER = "STOCK.TRANSFER_RECEIVE";

    private final IdempotencyGuard          guard;
    private final StockPostingService       posting;
    private final InventoryValuationService valuation;
    private final ObjectMapper              objectMapper;

    public TransferReceiveStockHandler(IdempotencyGuard guard,
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
        return DomainEventType.STOCK_TRANSFER_RECEIVED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("TransferReceiveStockHandler: event uid={} already processed — skipping",
                    event.getUid());
            return;
        }

        TransferReceivedPayload payload = deserialise(event.getPayload());

        // Deterministic 26-char per-leg, per-LINE source_event_uid keys (see MovementSourceKeys).
        // Leg codes 'R' / 'r' are distinct from the dispatch handler's 'D' / 'd'. Per-line indexing
        // matters here too: a transfer listing the same product twice reused one key per leg, so the
        // second line's movements were suppressed while transferCost moved the value for both.
        final MovementSourceKeys outKeys = MovementSourceKeys.forLeg(event.getUid(), 'R');
        final MovementSourceKeys inKeys  = MovementSourceKeys.forLeg(event.getUid(), 'r');

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(RequestContext.Principal.system(event.getCompanyId(), event.getBranchId()));
        try {
            for (TransferDispatchedPayload.LineItem line : payload.lines()) {
                final String legOutKey = outKeys.nextFor(line.productId());
                final String legInKey  = inKeys.nextFor(line.productId());

                // Probe before posting: transferCost below moves on_hand_value immediately, while
                // post() would no-op on an already-applied key. Skip the line whole on redelivery.
                if (posting.alreadyPosted(legOutKey, line.productId())) {
                    log.debug("TransferReceiveStockHandler: line for productId={} on transfer uid={} " +
                                    "already posted for this event — skipping (idempotent redelivery)",
                            line.productId(), payload.transferUid());
                    continue;
                }

                // (1) TRANSFER_OUT at in-transit pseudo-location (quantity decreases in transit).
                // Uses the 'R' leg key — distinct from the destination IN leg so the
                // (source_event_uid, product_id) idempotency backstop in StockPostingServiceImpl
                // does not suppress the destination TRANSFER_IN (STOCK-039).
                posting.post(
                        payload.companyId(), payload.destBranchId(), payload.inTransitLocationId(),
                        line.productId(), line.qtyInBase().negate(),
                        MovementType.TRANSFER_OUT,
                        legOutKey, "STOCK_TRANSFER", payload.transferUid(),
                        null, null, payload.receivedAt(),
                        null,
                        line.unitCostAmount(), line.valueAmount() != null ? line.valueAmount().negate() : null);

                // (2) TRANSFER_IN at destination location (quantity increases at dest).
                // Uses the 'r' leg key — distinct from the transit OUT leg above so both movements
                // are written and the destination on-hand is correctly credited.
                posting.post(
                        payload.companyId(), payload.destBranchId(), payload.destLocationId(),
                        line.productId(), line.qtyInBase(),
                        MovementType.TRANSFER_IN,
                        legInKey, "STOCK_TRANSFER", payload.transferUid(),
                        null, null, payload.receivedAt(),
                        null,
                        line.unitCostAmount(), line.valueAmount());

                // (3) Move on_hand_value from in-transit → dest so valuation stays correct (issue #12).
                valuation.transferCost(
                        payload.companyId(),
                        payload.destBranchId(), payload.inTransitLocationId(),
                        payload.destBranchId(), payload.destLocationId(),
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

    private TransferReceivedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, TransferReceivedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise TransferReceivedPayload: " + ex.getMessage(), ex);
        }
    }
}
