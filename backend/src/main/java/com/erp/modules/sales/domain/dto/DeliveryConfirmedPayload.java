package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Typed payload for the {@code DELIVERY.CONFIRMED} outbox event (ADR-0021 D-6).
 *
 * <p>Consumed by {@code DeliveryIssueStockHandler} in the stock module to issue stock + post COGS
 * for each delivered line at moving-average cost.
 *
 * <p>The {@link LineItem} shape is deliberately identical to
 * {@link SaleFinalisedPayload.LineItem} so the consumer reuses the same recipe-explosion + cost logic.
 */
public record DeliveryConfirmedPayload(
        String deliveryUid,
        String salesOrderUid,
        Long companyId,
        Long branchId,
        Instant deliveredAt,
        List<LineItem> lines
) {

    /**
     * Per-line item — what the stock consumer needs for stock deduction and recipe explosion.
     *
     * @param productId   internal product id (for stock ledger FK)
     * @param productUid  stable product uid (for cross-system references)
     * @param unitId      unit of measure id
     * @param qtyInBase   quantity in the product's base unit
     */
    public record LineItem(
            Long productId,
            String productUid,
            Long unitId,
            BigDecimal qtyInBase
    ) {}
}
