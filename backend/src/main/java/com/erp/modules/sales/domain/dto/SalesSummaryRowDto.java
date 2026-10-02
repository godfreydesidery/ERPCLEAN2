package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * One group of the Sales Summary (one customer, agent, route, branch, day or cashier).
 *
 * @param groupKey         the group's uid, or the ISO date for a DAY row; null for the
 *                         "(no route)" / "(not recorded)" group
 * @param groupLabel       what the group is called on the page
 * @param groupCode        the group's code (customer/agent/route/branch code, cashier username);
 *                         null for a DAY row
 * @param qty              quantity sold in BASE units, so packs and singles add up honestly
 * @param grossAmount      VAT-inclusive sales, in the company's base currency
 * @param netAmount        sales after VAT — the figure margin is measured against
 * @param costOfSales      cost of the goods at the moment of sale; NULL when any of the group's
 *                         stock was sold before it had ever been costed (unknown is not zero)
 * @param margin           net − cost of sales; null whenever the cost is
 * @param marginPercent    margin as a percentage of net; null when margin is, or net is zero
 * @param unknownCostItems how many invoice items in this group carry no cost
 * @param foreignCurrencyInvoices how many of the group's invoices were raised in a currency other
 *                         than the company's base currency; their amounts are included converted
 *                         at the rate stamped on each invoice when it was finalised (every money
 *                         figure on this row is in the base currency)
 */
public record SalesSummaryRowDto(
        String groupKey,
        String groupLabel,
        String groupCode,
        long invoiceCount,
        BigDecimal qty,
        BigDecimal grossAmount,
        BigDecimal discount,
        BigDecimal vatAmount,
        BigDecimal netAmount,
        BigDecimal costOfSales,
        BigDecimal margin,
        BigDecimal marginPercent,
        long unknownCostItems,
        long foreignCurrencyInvoices) {
}
