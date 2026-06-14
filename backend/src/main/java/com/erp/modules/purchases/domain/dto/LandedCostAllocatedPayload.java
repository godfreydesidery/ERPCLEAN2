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
        List<AllocationLine> lines,
        /** Human-readable landed cost number (e.g. LC-0001) — used in GL journal memos (FOLLOW-001). */
        String landedCostNumber
) {
    /** Back-compat: pre-FOLLOW-001 callers that do not supply landedCostNumber. */
    public LandedCostAllocatedPayload(String landedCostUid, Long companyId, Long branchId,
                                      BigDecimal totalAmount, String currency,
                                      List<AllocationLine> lines) {
        this(landedCostUid, companyId, branchId, totalAmount, currency, lines, null);
    }

    public record AllocationLine(
            Long goodsReceiptLineId,
            String goodsReceiptLineUid,
            Long productId,
            BigDecimal allocatedAmount
    ) {}
}
