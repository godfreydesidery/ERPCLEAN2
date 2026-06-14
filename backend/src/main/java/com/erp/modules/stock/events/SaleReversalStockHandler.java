package com.erp.modules.stock.events;

import com.erp.modules.sales.domain.dto.SaleVoidedPayload;
import com.erp.modules.stock.domain.entity.StockMovement;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockMovementRepository;
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
 * <p>ADR-0020: for each original SALE_ISSUE movement, reads the stored {@code value_amount}
 * (cost at time of issue) and calls {@link InventoryValuationService#reverseIssue} to restore
 * {@code on_hand_value}. Posts DR INVENTORY (1300) / CR COGS (5100) via
 * {@link InventoryGlPoster#postSaleReversalInNewTx} (REQUIRES_NEW — GL anomaly never poisons
 * the dispatch TX). If the original movement had no cost (null value_amount), the GL leg is
 * skipped with a WARN; quantity reversal still proceeds.
 */
@Component
public class SaleReversalStockHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SaleReversalStockHandler.class);

    static final String CONSUMER = "STOCK.SALE_REVERSAL";
    private static final String DOC_TYPE = "SALES_INVOICE";

    private final IdempotencyGuard           guard;
    private final StockPostingService        posting;
    private final StockMovementRepository    movementRepository;
    private final InventoryValuationService  valuation;
    private final InventoryGlPoster          glPoster;
    private final ObjectMapper               objectMapper;

    public SaleReversalStockHandler(IdempotencyGuard guard,
                                     StockPostingService posting,
                                     StockMovementRepository movementRepository,
                                     InventoryValuationService valuation,
                                     InventoryGlPoster glPoster,
                                     ObjectMapper objectMapper) {
        this.guard              = guard;
        this.posting            = posting;
        this.movementRepository = movementRepository;
        this.valuation          = valuation;
        this.glPoster           = glPoster;
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

        List<StockMovement> issued = movementRepository
                .findBySourceDocumentUidAndMovementType(payload.invoiceUid(), MovementType.SALE_ISSUE);

        if (issued.isEmpty()) {
            log.warn("SaleReversalStockHandler: SALE.VOIDED for invoice uid={} but no SALE_ISSUE " +
                            "movements found in ledger — anomaly recorded (OQ-STOCK-10). void event uid={}",
                    payload.invoiceUid(), event.getUid());
            guard.markProcessed(CONSUMER, event.getUid());
            return;
        }

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            BigDecimal totalOriginalValue = BigDecimal.ZERO;
            boolean anyCostNull = false;

            for (StockMovement original : issued) {
                BigDecimal originalValue = original.getValueAmount();

                if (originalValue == null) {
                    log.warn("SaleReversalStockHandler: original SALE_ISSUE movement uid={} has no " +
                                     "value_amount — valuation restore skipped for this row (D-2 edge)",
                            original.getUid());
                    anyCostNull = true;
                } else {
                    // Restore on_hand_value at original cost (ADR-0020 D-5).
                    // value_amount on a SALE_ISSUE is stored as a positive absolute value.
                    valuation.reverseIssue(
                            original.getCompanyId(), original.getBranchId(), original.getProductId(),
                            originalValue);
                    totalOriginalValue = totalOriginalValue.add(originalValue);
                }

                // Post opposite-sign SALE_REVERSAL quantity movement.
                posting.post(
                        original.getCompanyId(), original.getBranchId(), original.getProductId(),
                        original.getQuantity().negate(),  // SALE_ISSUE was negative → reversal is positive
                        MovementType.SALE_REVERSAL,
                        event.getUid(),
                        DOC_TYPE, payload.invoiceUid(),
                        null, null,
                        null,
                        null,
                        original.getUnitCostAmount(),
                        originalValue);
            }

            // Post one GL journal DR INVENTORY / CR COGS for the entire void (D-5).
            if (totalOriginalValue.compareTo(BigDecimal.ZERO) > 0) {
                String glEntryUid = glPoster.postSaleReversalInNewTx(
                        event.getCompanyId(), event.getBranchId(), LocalDate.now(),
                        payload.invoiceUid(), payload.invoiceNumber(), "TZS", totalOriginalValue);
                if (glEntryUid == null) {
                    log.warn("SaleReversalStockHandler: sale reversal GL post returned null " +
                                     "for invoice uid={} — GL not configured (qty still reversed)",
                            payload.invoiceUid());
                }
            } else if (anyCostNull) {
                log.warn("SaleReversalStockHandler: all original SALE_ISSUE movements for invoice uid={} " +
                                 "lacked value_amount — no COGS reversal GL posted",
                        payload.invoiceUid());
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
