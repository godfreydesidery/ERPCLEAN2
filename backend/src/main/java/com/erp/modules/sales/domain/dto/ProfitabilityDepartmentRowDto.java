package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * One department on the profitability report, to the column set of the client's own report
 * (Kilimanjaro sample, 31-Dec-2025). Every identity below was checked against three rows of that
 * sample and reproduces it exactly.
 *
 * <pre>
 *   netSales        = grossSales - discount            (still VAT-inclusive)
 *   netSales        = netAmount + vatAmount            (so netAmount is the VAT-exclusive sale)
 *   netAmount       = vatPortion + exemptPortion + zeroRatedPortion
 *   netContribution = netAmount - costOfSales
 *   marginPercent   = netContribution / netAmount      x 100
 *   markupPercent   = netContribution / costOfSales    x 100
 * </pre>
 *
 * <p><b>Department</b> is the product's {@code category}. It is free text with no master behind it,
 * so products nobody has classified group under a single "(no department)" row rather than being
 * dropped — a report that silently omits sales is worse than one that admits it cannot file them.
 *
 * @param costOfSales           null when any product in the department sold stock that had never
 *                              been costed. The cost visible is then an understatement of unknown
 *                              size, and so is any margin drawn from it, so neither is reported —
 *                              the same rule the honest-margin fix settled for the sales report.
 * @param netContribution       null whenever {@code costOfSales} is
 * @param marginPercent         null whenever {@code netContribution} is, and when there are no
 *                              sales to divide by
 * @param markupPercent         null whenever {@code netContribution} is, and when cost is zero —
 *                              markup on nothing is not infinite, it is unanswerable
 * @param productsWithUnknownCost how many products in this department could not be costed; shown so
 *                              a reader knows what the figures leave out
 */
public record ProfitabilityDepartmentRowDto(
        String     department,
        BigDecimal grossSales,
        BigDecimal discount,
        BigDecimal netSales,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        BigDecimal vatPortion,
        BigDecimal exemptPortion,
        BigDecimal zeroRatedPortion,
        BigDecimal costOfSales,
        BigDecimal netContribution,
        BigDecimal marginPercent,
        BigDecimal markupPercent,
        int        productsWithUnknownCost
) {
}
