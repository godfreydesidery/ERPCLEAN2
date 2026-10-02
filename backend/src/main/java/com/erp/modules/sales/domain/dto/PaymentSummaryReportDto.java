package com.erp.modules.sales.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.util.List;

/**
 * Daily cash-up / Payment Summary: what was taken from customers for finalised sales, per day,
 * cashier and payment method.
 *
 * @param branchName  null when every branch is covered
 * @param cashierName null when every cashier is covered
 * @param cashiers    everyone who appears in the report, for the cashier filter — the screen
 *                    offers these rather than the whole user list, which needs a different
 *                    permission
 * @param totals      one entry per currency, base currency first
 */
public record PaymentSummaryReportDto(
        ReportCompanyHeaderDto company,
        String fromDate,
        String toDate,
        String branchName,
        String cashierName,
        String baseCurrency,
        List<PaymentSummaryRowDto> rows,
        List<PaymentSummaryTotalsDto> totals,
        List<CashierRefDto> cashiers,
        String generatedAt) {

    /** A cashier the report mentions. */
    public record CashierRefDto(String uid, String name) {}
}
