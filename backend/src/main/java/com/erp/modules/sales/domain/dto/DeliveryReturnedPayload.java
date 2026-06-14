package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Outbox payload for DELIVERY.RETURNED (ADR-0021 D-11, Stage 2).
 *
 * <p>Consumed by {@link com.erp.modules.stock.events.DeliveryReturnStockHandler} to:
 * <ol>
 *   <li>Post a SALE_REVERSAL stock movement (stock back IN) per line.</li>
 *   <li>Call InventoryValuationService.reverseIssue at the original issued cost.</li>
 *   <li>Post one DR INVENTORY / CR COGS GL journal via InventoryGlPoster.postSaleReversalInNewTx.</li>
 * </ol>
 *
 * <p>{@code originalIssueValue} per line is the pro-rated original issued cost for the returned
 * quantity — derived from delivery_line.issue_value_amount × (qtyReturnedBase / qtyDeliveredBase),
 * HALF_UP 4 dp; null when avg_cost was not established at delivery time (no COGS to reverse).
 */
public record DeliveryReturnedPayload(
        String returnUid,
        String deliveryUid,
        Long companyId,
        Long branchId,
        Instant returnedAt,
        List<ReturnLineItem> lines,
        /** Human-readable sales return number for GL memo text (FOLLOW-001). */
        String returnNumber
) {
    /** Back-compat: pre-FOLLOW-001 callers that do not supply returnNumber. */
    public DeliveryReturnedPayload(String returnUid, String deliveryUid,
                                   Long companyId, Long branchId,
                                   Instant returnedAt, List<ReturnLineItem> lines) {
        this(returnUid, deliveryUid, companyId, branchId, returnedAt, lines, null);
    }
    /**
     * Per-line item for the DELIVERY.RETURNED payload.
     *
     * @param productId          the product
     * @param productUid         uid for recipe explosion
     * @param unitId             base unit
     * @param qtyReturnedBase    quantity returned in base units (positive)
     * @param originalIssueValue pro-rated original issued cost for this returned qty; null = no COGS to reverse
     */
    public record ReturnLineItem(
            Long productId,
            String productUid,
            Long unitId,
            BigDecimal qtyReturnedBase,
            BigDecimal originalIssueValue
    ) {}
}
