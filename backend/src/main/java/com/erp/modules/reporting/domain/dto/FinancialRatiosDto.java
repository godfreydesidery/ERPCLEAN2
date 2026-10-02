package com.erp.modules.reporting.domain.dto;

import java.util.List;

/**
 * Financial ratios for a period, computed only from the Income Statement over
 * [fromDate, toDate] and the Balance Sheet as-at toDate (closing) and fromDate − 1 (opening).
 *
 * @param periodDays               length of the period in days, used by the turnover-days ratios
 * @param incomeStatementTies      the Income Statement's own self-check bar
 * @param balanceSheetTies         the Balance Sheet's own self-check bar (closing date)
 * @param notes                    reading notes that apply to every ratio
 */
public record FinancialRatiosDto(
        StatementHeaderDto      header,
        ReportCompanyHeaderDto  company,
        long                    periodDays,
        List<FinancialRatioDto> ratios,
        boolean                 incomeStatementTies,
        boolean                 balanceSheetTies,
        List<String>            notes
) {}
