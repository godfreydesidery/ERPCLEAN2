package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * Grand totals of the Sales Summary.
 *
 * <p>The sales columns (invoices, qty, gross, discount, VAT, net) cover every group. The cost side
 * does not pretend to: {@code costOfSales} and {@code margin} sum only the groups whose cost is
 * fully known, {@code marginPercent} is measured against the net of those same groups, and
 * {@code groupsWithUnknownCost} says how many groups were left out, so a partial margin is never
 * read as a complete one.
 *
 * @param unknownCostItems invoice items, across the whole report, sold before their stock was costed
 */
public record SalesSummaryTotalsDto(
        long invoiceCount,
        BigDecimal qty,
        BigDecimal grossAmount,
        BigDecimal discount,
        BigDecimal vatAmount,
        BigDecimal netAmount,
        BigDecimal costOfSales,
        BigDecimal margin,
        BigDecimal marginPercent,
        int groupsWithUnknownCost,
        long unknownCostItems) {
}
