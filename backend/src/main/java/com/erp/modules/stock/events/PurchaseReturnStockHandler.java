package com.erp.modules.stock.events;

import com.erp.modules.purchases.domain.dto.PurchaseReturnedPayload;
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
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code PURCHASE.RETURNED} (ADR-0027 D-7 / D-10).
 *
 * <p>For each return line, calls {@link InventoryValuationService#reverseReceipt} to back out the
 * moving-average at original receipt cost, then posts a negative PURCHASE_RETURN stock movement
 * (quantity leaves inventory). Posts one GL journal DR GRNI (2150) / CR INVENTORY (1300) via
 * {@link InventoryGlPoster#postPurchaseReturnInNewTx} (REQUIRES_NEW — GL anomaly never poisons
 * the dispatch TX).
 */
@Component
public class PurchaseReturnStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PurchaseReturnStockHandler.class);

    static final String CONSUMER = "STOCK.PURCHASE_RETURN";
    private static final String DOC_TYPE = "PURCHASE_RETURN";

    private final IdempotencyGuard          guard;
    private final StockPostingService       posting;
    private final InventoryValuationService valuation;
    private final InventoryGlPoster         glPoster;
    private final ObjectMapper              objectMapper;

    public PurchaseReturnStockHandler(IdempotencyGuard guard,
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
        return DomainEventType.PURCHASE_RETURNED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("PurchaseReturnStockHandler: event uid={} already processed — skipping", event.getUid());
            return;
        }

        PurchaseReturnedPayload payload = deserialise(event.getPayload());

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            BigDecimal totalReturnValue = BigDecimal.ZERO;
            String currency = payload.currency() != null ? payload.currency() : "TZS";

            for (PurchaseReturnedPayload.ReturnLine line : payload.lines()) {
                BigDecimal lineValue = line.lineValue();

                if (lineValue != null && lineValue.signum() > 0) {
                    // Back out the receipt from the moving-average at original cost (ADR-0020 D-5)
                    valuation.reverseReceipt(
                            payload.companyId(), payload.branchId(), line.productId(),
                            line.returnedQtyInBase().abs(), lineValue);
                    totalReturnValue = totalReturnValue.add(lineValue);
                } else {
                    log.warn("PurchaseReturnStockHandler: return line grLineId={} has no lineValue " +
                                     "— avg recompute skipped for this row", line.goodsReceiptLineId());
                }

                // Post negative stock movement: qty leaves inventory
                posting.post(
                        payload.companyId(), payload.branchId(), line.productId(),
                        line.returnedQtyInBase().negate(),  // negative = stock out
                        MovementType.PURCHASE_RETURN,
                        event.getUid(),
                        DOC_TYPE, payload.purchaseReturnUid(),
                        null, null,
                        null,
                        null,
                        line.unitCostAmount(),
                        lineValue);
                // movement uid recorded on the return header by PurchaseReturnService
            }

            // GL: DR GRNI / CR INVENTORY — one journal for the full return (ADR-0027 D-7).
            // When billed=true the GRNI was already cleared to AP; the DR GRNI here re-opens it
            // as a temporary accrual.  The AP debit note raised synchronously in the confirm TX
            // (DR AP / CR Purchases) handles the payable side.  The GRNI will net to zero once
            // the next bill-match posts DR GRNI / CR AP — or the finance team manually clears it.
            // See ADR-0027 OQ-RETURN-GL for the accepted trade-off.
            if (totalReturnValue.signum() > 0) {
                if (payload.billed()) {
                    log.warn("PurchaseReturnStockHandler: return={} marks receipt as already " +
                                     "billed — posting DR GRNI / CR INVENTORY re-opens GRNI that " +
                                     "was cleared by the matched bill.  Verify GRNI account (2150) " +
                                     "reconciliation after the next bill-match (ADR-0027 OQ-RETURN-GL).",
                            payload.purchaseReturnUid());
                }
                String glEntryUid = glPoster.postPurchaseReturnInNewTx(
                        payload.companyId(), payload.branchId(), LocalDate.now(),
                        payload.purchaseReturnUid(), currency, totalReturnValue);
                if (glEntryUid == null) {
                    log.warn("PurchaseReturnStockHandler: GL post returned null for return={} " +
                                     "— GRNI/INVENTORY not configured (qty still reversed)",
                            payload.purchaseReturnUid());
                }
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

    private PurchaseReturnedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, PurchaseReturnedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise PurchaseReturnedPayload: " + ex.getMessage(), ex);
        }
    }
}
