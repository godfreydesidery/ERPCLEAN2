package com.erp.modules.sales.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.sales.domain.enums.SalesSummaryGroupBy;
import java.util.List;

/**
 * Sales Summary — finalised sales over a period, one row per customer / agent / route / branch /
 * day / cashier, with cost of sales and margin.
 *
 * @param branchName null when the report covers every branch
 */
public record SalesSummaryReportDto(
        ReportCompanyHeaderDto company,
        String fromDate,
        String toDate,
        SalesSummaryGroupBy groupBy,
        String branchName,
        String currency,
        List<SalesSummaryRowDto> rows,
        SalesSummaryTotalsDto totals,
        String generatedAt,
        /**
         * False when the caller may not see cost (no {@code INVENTORY.VALUATION.VIEW}, owner ruling
         * 2026-10-10 / ADM-14): cost of sales, margin and margin % are then null because they were
         * WITHHELD, not because the cost is unknown. Additive — older clients ignore it.
         */
        boolean costVisible) {

    /** The original shape: cost visible. */
    public SalesSummaryReportDto(ReportCompanyHeaderDto company, String fromDate, String toDate,
                                 SalesSummaryGroupBy groupBy, String branchName, String currency,
                                 List<SalesSummaryRowDto> rows, SalesSummaryTotalsDto totals,
                                 String generatedAt) {
        this(company, fromDate, toDate, groupBy, branchName, currency, rows, totals, generatedAt,
                true);
    }

    /** This report with cost of sales, margin and margin % withheld (null), field names unchanged. */
    public SalesSummaryReportDto withoutCost() {
        List<SalesSummaryRowDto> masked = rows.stream()
                .map(r -> new SalesSummaryRowDto(r.groupKey(), r.groupLabel(), r.groupCode(),
                        r.invoiceCount(), r.qty(), r.grossAmount(), r.discount(), r.vatAmount(),
                        r.netAmount(), null, null, null, 0L, r.foreignCurrencyInvoices()))
                .toList();
        SalesSummaryTotalsDto t = totals == null ? null : new SalesSummaryTotalsDto(
                totals.invoiceCount(), totals.qty(), totals.grossAmount(), totals.discount(),
                totals.vatAmount(), totals.netAmount(), null, null, null, 0, 0L);
        return new SalesSummaryReportDto(company, fromDate, toDate, groupBy, branchName, currency,
                masked, t, generatedAt, false);
    }
}
