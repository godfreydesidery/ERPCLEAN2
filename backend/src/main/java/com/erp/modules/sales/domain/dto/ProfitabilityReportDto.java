package com.erp.modules.sales.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.util.List;

/**
 * Profitability Report (K-2026-08-30 #2) — gross sales, VAT, net, cost of sales and profit, per
 * product and in total, over a date range.
 *
 * <p>Distinct from the Income Statement, deliberately. That one is built from the GENERAL LEDGER,
 * carries every other cost the business incurs, and never shows VAT (output VAT is a liability, not
 * a P&amp;L line). This one is built from the SALES INVOICES and the stock issued against them, so
 * it answers "what did we make on what we sold" for a shopkeeper who has not posted a journal in
 * their life. Both are correct; they answer different questions, and the screen says which.
 *
 * @param branchName null = every branch in the company
 */
public record ProfitabilityReportDto(
        ReportCompanyHeaderDto  company,
        String                  fromDate,
        String                  toDate,
        String                  branchName,
        String                  currency,
        List<ProfitabilityRowDto> rows,
        ProfitabilityTotalsDto  totals,
        String                  generatedAt
) {
}
