package com.erp.modules.stock.events;

import com.erp.modules.ap.domain.dto.BillCostVariancePayload;
import com.erp.modules.stock.service.InventoryValuationService;
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
 * Consumes {@code AP.BILL.COST_VARIANCE} (ACC-17 / LBO-13 / PUR-21).
 *
 * <p>A supplier bill was matched at a cost different from its goods receipt. The AP journal has
 * already debited (or credited) GL Inventory with the share of that difference belonging to goods
 * still on hand; this handler moves the product's stock value and moving-average cost by the same
 * amount, through the landed-cost capitalisation path (a cost change on goods already in stock is
 * exactly what that path does), so Σ on_hand_value keeps tying to GL 1300. No GL is posted here.
 */
@Component
public class BillCostVarianceStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(BillCostVarianceStockHandler.class);

    static final String CONSUMER = "STOCK.BILL_COST_VARIANCE";

    private final IdempotencyGuard          guard;
    private final InventoryValuationService valuation;
    private final ObjectMapper              objectMapper;

    public BillCostVarianceStockHandler(IdempotencyGuard guard,
                                        InventoryValuationService valuation,
                                        ObjectMapper objectMapper) {
        this.guard        = guard;
        this.valuation    = valuation;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.BILL_COST_VARIANCE;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("BillCostVarianceStockHandler: event uid={} already processed — skipping",
                    event.getUid());
            return;
        }

        BillCostVariancePayload payload = deserialise(event.getPayload());

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(RequestContext.Principal.system(event.getCompanyId(), event.getBranchId()));
        try {
            for (BillCostVariancePayload.Line line : payload.lines()) {
                if (line.amount() == null || line.amount().signum() == 0) {
                    continue;
                }
                valuation.applyLandedCost(payload.companyId(), payload.branchId(),
                        line.productId(), line.amount());
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

    private BillCostVariancePayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, BillCostVariancePayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise BillCostVariancePayload: " + ex.getMessage(), ex);
        }
    }
}
