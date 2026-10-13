package com.erp.modules.ap.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Outbox payload for {@code AP.BILL.COST_VARIANCE} (ACC-17 / LBO-13 / PUR-21).
 *
 * <p>When a matched bill's goods cost differs from what their goods receipt booked, the AP journal
 * clears GRNI at the receipt value and puts the difference to Inventory (the share of the goods
 * still on hand) and COGS (the share already gone). Each line here is the Inventory share for one
 * product, in BASE currency, signed: positive raises the product's stock value, negative lowers it.
 * The stock module applies it to the company-wide moving average and posts no GL of its own.
 */
public record BillCostVariancePayload(
        String supplierBillUid,
        Long companyId,
        Long branchId,
        String billNumber,
        List<Line> lines
) {
    public record Line(Long productId, BigDecimal amount) {}
}
