package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * One product's trading result over the reporting window (K-2026-08-30 #2: "a profitability report
 * showing Gross Sales, VAT Amount, Net Amount, Cost of Sales and profit amount").
 *
 * <p>The five figures are exactly the client's five words, in the client's order, and they tie
 * together on the page: {@code grossSales − vatAmount = netAmount}, and
 * {@code netAmount − costOfSales = profit}. A reader who cannot reproduce the arithmetic on a
 * printed report stops trusting it.
 *
 * @param qtySold     units sold in the window, in the product's base unit
 * @param grossSales  VAT-INCLUSIVE turnover — what the customer was charged
 * @param vatAmount   output VAT within that turnover
 * @param netAmount   turnover net of VAT ({@code grossSales − vatAmount}); this is the revenue
 *                    figure profit is measured against, never the gross
 * @param costOfSales cost of the goods that left the shelf, taken from the SALE_ISSUE stock
 *                    movement posted for the same invoice — or {@code null} when some of this
 *                    product's stock was sold before any cost was ever established for it. Null is
 *                    "we cannot tell you", NOT zero: a zero cost would report the entire sale as
 *                    profit, which is the defect the honest-margin fix already corrected once on
 *                    the Sales Report
 * @param profit      {@code netAmount − costOfSales}, or null whenever the cost is null — a profit
 *                    derived from an understated cost overstates the result by an unknown amount
 */
public record ProfitabilityRowDto(
        String     productCode,
        String     productName,
        BigDecimal qtySold,
        BigDecimal grossSales,
        BigDecimal vatAmount,
        BigDecimal netAmount,
        BigDecimal costOfSales,
        BigDecimal profit
) {
}
