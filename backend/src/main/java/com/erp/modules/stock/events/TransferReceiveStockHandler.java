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
 * <p><strong>Per-leg idempotency key (STOCK-039 fix):</strong> mirrors the logic in
 * {@link TransferDispatchStockHandler} — the {@code source_event_uid} column is {@code VARCHAR(26)};
 * appending a text suffix to the 26-char ULID event uid would overflow the column. The fix
 * truncates to 24 chars and appends a 2-char leg code ({@code R1} / {@code R2}) to produce a
 * 26-char deterministic key per leg, ensuring the (source_event_uid, product_id) backstop in
 * {@link com.erp.modules.stock.service.StockPostingServiceImpl} correctly identifies each leg on
 * redelivery without suppressing the destination TRANSFER_IN.
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

        // Deterministic 26-char per-leg source_event_uid keys (STOCK-039 fix — see class javadoc).
        // Leg codes "R1" / "R2" are distinct from the dispatch handler's "D1" / "D2" so a product
        // can have both a dispatch and a receive event in the backstop without collision.
        final String legOutKey = event.getUid().substring(0, 24) + "R1";
        final String legInKey  = event.getUid().substring(0, 24) + "R2";

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            for (TransferDispatchedPayload.LineItem line : payload.lines()) {

                // (1) TRANSFER_OUT at in-transit pseudo-location (quantity decreases in transit).
                // Uses leg key "R1" — distinct from the destination IN leg so the
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
                // Uses leg key "R2" — distinct from the transit OUT leg above so both movements
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
