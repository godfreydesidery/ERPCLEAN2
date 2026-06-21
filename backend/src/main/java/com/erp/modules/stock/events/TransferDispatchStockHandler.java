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
 *
 * <p><strong>Per-leg idempotency key (STOCK-039 fix):</strong> each leg of a dispatch requires a
 * distinct {@code source_event_uid} so the (source_event_uid, product_id) idempotency backstop in
 * {@link com.erp.modules.stock.service.StockPostingServiceImpl} does not suppress the second leg.
 * The {@code source_event_uid} column is {@code VARCHAR(26)} — a raw ULID event uid is exactly 26
 * chars, so appending {@code ":OUT"} / {@code ":IN"} overflows the column (PostgreSQL raises
 * {@code 22001 value too long}, rolling back the transaction). The fix computes a 26-char
 * deterministic key by truncating the event uid to 24 chars and appending a 2-char leg code
 * ({@code D1} / {@code D2} for Dispatch legs 1 and 2). This is safe and idempotent: the same
 * event uid always produces the same per-leg key, which the backstop check finds on redelivery.
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

        // Deterministic 26-char per-leg source_event_uid keys (STOCK-039 fix — see class javadoc).
        // The source_event_uid column is VARCHAR(26); appending a suffix to the 26-char ULID overflows.
        // Truncate to 24 chars and append a 2-char leg code that is unique within this handler.
        final String legOutKey = event.getUid().substring(0, 24) + "D1";
        final String legInKey  = event.getUid().substring(0, 24) + "D2";

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            for (TransferDispatchedPayload.LineItem line : payload.lines()) {

                // (1) TRANSFER_OUT at source location (quantity decreases).
                // Uses leg key "D1" — distinct from the in-transit IN leg so the
                // (source_event_uid, product_id) idempotency backstop in StockPostingServiceImpl
                // does not suppress the second leg (STOCK-039).
                posting.post(
                        payload.companyId(), payload.sourceBranchId(), payload.sourceLocationId(),
                        line.productId(), line.qtyInBase().negate(),
                        MovementType.TRANSFER_OUT,
                        legOutKey, "STOCK_TRANSFER", payload.transferUid(),
                        null, null, payload.dispatchedAt(),
                        null,
                        line.unitCostAmount(), line.valueAmount() != null ? line.valueAmount().negate() : null);

                // (2) TRANSFER_IN at in-transit pseudo-location (quantity increases).
                // Uses leg key "D2" — distinct from the source OUT leg above so both movements
                // are written and the in-transit holding balance is established correctly.
                posting.post(
                        payload.companyId(), payload.sourceBranchId(), payload.inTransitLocationId(),
                        line.productId(), line.qtyInBase(),
                        MovementType.TRANSFER_IN,
                        legInKey, "STOCK_TRANSFER", payload.transferUid(),
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
