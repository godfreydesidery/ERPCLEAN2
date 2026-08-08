package com.erp.modules.stock.events;

import com.erp.modules.sales.domain.dto.DeliveryReturnedPayload;
import com.erp.modules.stock.domain.enums.MovementType;
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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code DELIVERY.RETURNED} — posts stock IN (SALE_REVERSAL) and DR INVENTORY / CR COGS
 * at the <em>original issued cost</em> for each returned line (ADR-0021 D-11, Stage 2).
 *
 * <p>Mirrors {@link SaleReversalStockHandler} and {@link DeliveryIssueStockHandler}:
 * <ul>
 *   <li>source_document_type = "SALES_RETURN"</li>
 *   <li>source_document_uid  = returnUid</li>
 *   <li>sourceRef for the COGS-reversal journal = returnUid</li>
 * </ul>
 *
 * <p>Cost reversal uses the {@code originalIssueValue} from the payload (pre-computed
 * pro-rata from {@code delivery_line.issue_value_amount}) — the ADR-0021 D-11 authoritative
 * per-line original cost. When null (avg_cost was not established at delivery), the valuation
 * restore and the GL leg are skipped (WARN only; quantity still moves, BR-INV-10 edge).
 *
 * <p>Idempotency: {@link IdempotencyGuard} consumer "STOCK.DELIVERY_RETURN" + DB backstop
 * {@code uq_stock_movement_source_event (source_event_uid, product_id)}.
 */
@Component
public class DeliveryReturnStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(DeliveryReturnStockHandler.class);

    static final String CONSUMER  = "STOCK.DELIVERY_RETURN";
    private static final String DOC_TYPE = "SALES_RETURN";

    private final IdempotencyGuard          guard;
    private final StockPostingService       posting;
    private final InventoryValuationService valuation;
    private final InventoryGlPoster         glPoster;
    private final ObjectMapper              objectMapper;

    public DeliveryReturnStockHandler(IdempotencyGuard guard,
                                      StockPostingService posting,
                                      InventoryValuationService valuation,
                                      InventoryGlPoster glPoster,
                                      ObjectMapper objectMapper) {
        this.guard        = guard;
        this.posting      = posting;
        this.valuation    = valuation;
        this.glPoster     = glPoster;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.DELIVERY_RETURNED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("DeliveryReturnStockHandler: event uid={} already processed — skipping",
                    event.getUid());
            return;
        }

        DeliveryReturnedPayload payload = deserialise(event.getPayload());

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            BigDecimal totalOriginalValue = BigDecimal.ZERO;
            boolean anyCostNull = false;

            // One idempotency key per LINE — see MovementSourceKeys. A single event-wide key
            // silently dropped every line after the first for a repeated product.
            MovementSourceKeys keys = MovementSourceKeys.forEvent(event.getUid());

            for (DeliveryReturnedPayload.ReturnLineItem line : payload.lines()) {
                BigDecimal lineValue = processLine(event, payload, line, keys);
                if (lineValue == null) {
                    anyCostNull = true;
                } else {
                    totalOriginalValue = totalOriginalValue.add(lineValue);
                }
            }

            // Post one DR INVENTORY / CR COGS GL journal for the entire return (ADR-0021 D-11)
            if (totalOriginalValue.compareTo(BigDecimal.ZERO) > 0) {
                LocalDate postingDate = payload.returnedAt() != null
                        ? payload.returnedAt().atZone(ZoneOffset.UTC).toLocalDate()
                        : LocalDate.now();
                String glEntryUid = glPoster.postSaleReversalInNewTx(
                        event.getCompanyId(), event.getBranchId(), postingDate,
                        payload.returnUid(), payload.returnNumber(), "TZS", totalOriginalValue);
                if (glEntryUid == null) {
                    log.warn("DeliveryReturnStockHandler: COGS-reversal GL post returned null " +
                                     "for return uid={} — GL not configured (qty still reversed)",
                            payload.returnUid());
                }
            } else if (anyCostNull) {
                log.warn("DeliveryReturnStockHandler: all lines for return uid={} lacked " +
                                 "originalIssueValue — no COGS-reversal GL posted",
                        payload.returnUid());
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

    /**
     * Posts the SALE_REVERSAL movement for one return line and calls reverseIssue.
     *
     * @return the line's original issue value (positive), or null if none.
     */
    private BigDecimal processLine(DomainEvent event, DeliveryReturnedPayload payload,
                                    DeliveryReturnedPayload.ReturnLineItem line,
                                    MovementSourceKeys keys) {
        // Allocated before any early return so a skipped line still consumes its slot.
        String sourceKey = keys.nextFor(line.productId());

        BigDecimal returnQty = line.qtyReturnedBase();
        if (returnQty == null || returnQty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("DeliveryReturnStockHandler: zero/null returnQty for productId={} on return uid={} — skipped",
                    line.productId(), payload.returnUid());
            return null;
        }

        // Probe before reverseIssue — it mutates on_hand_value immediately, while post() would
        // no-op on an already-applied key.
        if (posting.alreadyPosted(sourceKey, line.productId())) {
            log.debug("DeliveryReturnStockHandler: line for productId={} on return uid={} already " +
                            "posted for this event — skipping (idempotent redelivery)",
                    line.productId(), payload.returnUid());
            return null;
        }

        BigDecimal originalValue = line.originalIssueValue();

        if (originalValue != null && originalValue.compareTo(BigDecimal.ZERO) > 0) {
            // Restore on_hand_value at the original issued cost (ADR-0020 D-5 reverseIssue)
            valuation.reverseIssue(
                    event.getCompanyId(), event.getBranchId(), line.productId(), originalValue);
        } else {
            log.warn("DeliveryReturnStockHandler: no originalIssueValue for productId={} on return uid={} " +
                             "— valuation restore skipped (qty still IN)",
                    line.productId(), payload.returnUid());
        }

        // Compute unit cost for the movement row (diagnostic only; cost came from original issue)
        BigDecimal unitCost = null;
        if (originalValue != null && originalValue.compareTo(BigDecimal.ZERO) > 0) {
            unitCost = originalValue.divide(returnQty, 4, RoundingMode.HALF_UP);
        }

        // Post SALE_REVERSAL movement — positive qty = stock IN (ADR-0021 D-11)
        posting.post(
                event.getCompanyId(), event.getBranchId(), line.productId(),
                returnQty,                    // positive — stock comes back IN
                MovementType.SALE_REVERSAL,
                sourceKey,
                DOC_TYPE, payload.returnUid(),
                null, null,
                payload.returnedAt(),
                null,
                unitCost,
                originalValue);

        return originalValue != null && originalValue.compareTo(BigDecimal.ZERO) > 0
                ? originalValue : null;
    }

    private DeliveryReturnedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, DeliveryReturnedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise DeliveryReturnedPayload: " + ex.getMessage(), ex);
        }
    }
}
