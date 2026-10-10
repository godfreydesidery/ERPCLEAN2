package com.erp.modules.sales.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.util.List;

/**
 * Per-product Sales Register over a date range (SAM Electronix go-live, FR-SALES reporting).
 * {@code supplierName}/{@code agentName}/{@code routeName} are non-null only when the
 * corresponding filter was applied.
 */
public record SalesReportDto(
        ReportCompanyHeaderDto  company,
        String                  fromDate,
        String                  toDate,
        String                  supplierName,
        String                  agentName,
        String                  routeName,
        String                  currency,
        List<SalesReportRowDto> rows,
        SalesReportTotalsDto    totals,
        String                  generatedAt,
        /**
         * False when the caller may not see cost (no {@code INVENTORY.VALUATION.VIEW}, owner ruling
         * 2026-10-10 / ADM-14): every margin is then null because it was WITHHELD, not because the
         * cost is unknown. Additive — older clients ignore it.
         */
        boolean                 costVisible) {

    /** The original shape: cost visible. */
    public SalesReportDto(ReportCompanyHeaderDto company, String fromDate, String toDate,
                          String supplierName, String agentName, String routeName,
                          String currency, List<SalesReportRowDto> rows,
                          SalesReportTotalsDto totals, String generatedAt) {
        this(company, fromDate, toDate, supplierName, agentName, routeName, currency, rows,
                totals, generatedAt, true);
    }

    /**
     * This report with margin withheld: every row's and the total's margin is null, and
     * {@code marginRowsUnknown} counts every row so an older client that reads only that count
     * shows "no margin" rather than a margin of zero. Field names are unchanged (deployed apps
     * parse them).
     */
    public SalesReportDto withoutCost() {
        List<SalesReportRowDto> masked = rows.stream()
                .map(r -> new SalesReportRowDto(r.productCode(), r.productName(), r.currentStock(),
                        r.qtySold(), r.discount(), r.vat(), null, r.amount()))
                .toList();
        SalesReportTotalsDto t = totals == null ? null : new SalesReportTotalsDto(
                totals.qtySold(), totals.discount(), totals.vat(), null, totals.amount(),
                masked.size());
        return new SalesReportDto(company, fromDate, toDate, supplierName, agentName, routeName,
                currency, masked, t, generatedAt, false);
    }
}
