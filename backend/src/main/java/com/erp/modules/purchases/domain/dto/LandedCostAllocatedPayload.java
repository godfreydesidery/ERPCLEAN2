package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Outbox payload for {@code LANDED_COST.ALLOCATED} (ADR-0027 D-5 / D-10).
 * Each line carries the product and allocation amount for one GR line so the
 * {@link com.erp.modules.stock.events.LandedCostStockHandler} can update moving-average
 * on each affected product.
 */
public record LandedCostAllocatedPayload(
        String landedCostUid,
        Long companyId,
        Long branchId,
        BigDecimal totalAmount,
        String currency,
        List<AllocationLine> lines
) {

    public record AllocationLine(
            Long goodsReceiptLineId,
            String goodsReceiptLineUid,
            Long productId,
            BigDecimal allocatedAmount
    ) {}
}
