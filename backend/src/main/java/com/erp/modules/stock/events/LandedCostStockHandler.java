package com.erp.modules.stock.events;

import com.erp.modules.purchases.domain.dto.LandedCostAllocatedPayload;
import com.erp.modules.stock.service.InventoryGlPoster;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code LANDED_COST.ALLOCATED} (ADR-0027 D-5 / D-10).
 *
 * <p>For each allocation line, calls {@link InventoryValuationService#applyLandedCost} to add the
 * allocated amount to the product's on_hand_value and recompute avg_cost (moving-average update).
 * Posts one GL journal DR INVENTORY (1300) / CR LANDED_COST_CLEARING (2160) for the total via
 * {@link InventoryGlPoster#postLandedCostInNewTx} (REQUIRES_NEW — GL anomaly never poisons the
 * dispatch TX).
 */
@Component
public class LandedCostStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(LandedCostStockHandler.class);

    static final String CONSUMER = "STOCK.LANDED_COST";

    private final IdempotencyGuard          guard;
    private final InventoryValuationService valuation;
    private final InventoryGlPoster         glPoster;
    private final ObjectMapper              objectMapper;

    public LandedCostStockHandler(IdempotencyGuard guard,
                                   InventoryValuationService valuation,
                                   InventoryGlPoster glPoster,
                                   ObjectMapper objectMapper) {
        this.guard        = guard;
        this.valuation    = valuation;
        this.glPoster     = glPoster;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.LANDED_COST_ALLOCATED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("LandedCostStockHandler: event uid={} already processed — skipping", event.getUid());
            return;
        }

        LandedCostAllocatedPayload payload = deserialise(event.getPayload());

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            // Sum allocation lines as the authoritative GL amount (ADR-0027 D-5 / adversarial fix).
            // Using payload.totalAmount() is unsafe: if the field is null, zero, or corrupted during
            // serialisation the GL post would be skipped while inventory value was already updated,
            // breaking the DR INVENTORY / CR LANDED_COST_CLEARING balance.  The line sum is always
            // consistent with what was capitalised (the largest-remainder allocation loop guarantees
            // Σ allocated == totalCharge exactly).
            BigDecimal glAmount = BigDecimal.ZERO;
            for (LandedCostAllocatedPayload.AllocationLine line : payload.lines()) {
                valuation.applyLandedCost(
                        payload.companyId(), payload.branchId(),
                        line.productId(), line.allocatedAmount());
                if (line.allocatedAmount() != null) {
                    glAmount = glAmount.add(line.allocatedAmount());
                }
            }

            // GL: DR INVENTORY / CR LANDED_COST_CLEARING — one journal per landed cost confirm.
            // Amount = sum of per-line allocations (not payload.totalAmount() — see above).
            if (glAmount.signum() > 0) {
                String glEntryUid = glPoster.postLandedCostInNewTx(
                        payload.companyId(), payload.branchId(), LocalDate.now(),
                        payload.landedCostUid(),
                        payload.currency() != null ? payload.currency() : "TZS",
                        glAmount);
                if (glEntryUid == null) {
                    log.warn("LandedCostStockHandler: GL post returned null for landedCost={} " +
                                     "— LANDED_COST_CLEARING not configured (valuation still updated)",
                            payload.landedCostUid());
                }
            } else {
                log.warn("LandedCostStockHandler: computed GL amount is zero for landedCost={} " +
                                 "— no allocation lines had a positive amount; GL post skipped",
                        payload.landedCostUid());
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

    private LandedCostAllocatedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, LandedCostAllocatedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise LandedCostAllocatedPayload: " + ex.getMessage(), ex);
        }
    }
}
