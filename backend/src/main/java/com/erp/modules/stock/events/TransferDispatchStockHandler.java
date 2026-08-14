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
 * <p><strong>Per-leg, per-line idempotency key (STOCK-039 + the repeated-product fix):</strong> each
 * movement needs a distinct {@code source_event_uid} so the (source_event_uid, product_id)
 * idempotency backstop in {@link com.erp.modules.stock.service.StockPostingServiceImpl} does not
 * suppress it. Two distinctions are needed, not one: the OUT and IN legs of a line, and the same
 * product appearing on two lines of the transfer. Per-leg keys alone fixed only the first — a
 * transfer listing a product twice posted its second line nowhere while {@code transferCost} moved
 * the value for both. Keys come from {@link MovementSourceKeys} (leg {@code 'D'} = source OUT,
 * {@code 'd'} = in-transit IN), which also explains why the 26-char column forbids simply appending
 * a suffix to the 26-char ULID.
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

        // Deterministic 26-char per-leg, per-LINE source_event_uid keys (see MovementSourceKeys).
        // Per-leg alone was not enough: a transfer listing the same product on two lines reused the
        // same leg key for both, so the backstop suppressed the second line's OUT and IN entirely.
        // The two sequences advance in lockstep, so a line's OUT and IN share an index.
        final MovementSourceKeys outKeys = MovementSourceKeys.forLeg(event.getUid(), 'D');
        final MovementSourceKeys inKeys  = MovementSourceKeys.forLeg(event.getUid(), 'd');

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(RequestContext.Principal.system(event.getCompanyId(), event.getBranchId()));
        try {
            for (TransferDispatchedPayload.LineItem line : payload.lines()) {
                final String legOutKey = outKeys.nextFor(line.productId());
                final String legInKey  = inKeys.nextFor(line.productId());

                // Probe before posting: transferCost below moves on_hand_value immediately, while
                // post() would no-op on an already-applied key. Skip the line whole on redelivery.
                if (posting.alreadyPosted(legOutKey, line.productId())) {
                    log.debug("TransferDispatchStockHandler: line for productId={} on transfer uid={} " +
                                    "already posted for this event — skipping (idempotent redelivery)",
                            line.productId(), payload.transferUid());
                    continue;
                }

                // (1) TRANSFER_OUT at source location (quantity decreases).
                // Uses the 'D' leg key — distinct from the in-transit IN leg so the
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
                // Uses the 'd' leg key — distinct from the source OUT leg above so both movements
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
