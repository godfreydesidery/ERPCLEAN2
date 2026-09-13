package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * The foot of the Profitability Report — and, for the manager who asked for it, the whole answer:
 * the shop's gross sales, VAT, net, cost of sales and profit for the period on one line.
 *
 * <p>{@code costOfSales} and {@code profit} sum only the rows whose cost is actually known.
 * {@code rowsWithUnknownCost} travels beside them so the page can say so out loud: without the
 * count, a total that omits some cost of sales reads as complete and OVERSTATES profit, which is
 * precisely the complaint that produced the honest-margin fix on the Sales Report.
 *
 * @param rowsWithUnknownCost products excluded from {@code costOfSales} and {@code profit} because
 *                            some of their stock was sold before it had ever been costed
 */
public record ProfitabilityTotalsDto(
        BigDecimal qtySold,
        BigDecimal grossSales,
        BigDecimal vatAmount,
        BigDecimal netAmount,
        BigDecimal costOfSales,
        BigDecimal profit,
        int        rowsWithUnknownCost
) {
}
