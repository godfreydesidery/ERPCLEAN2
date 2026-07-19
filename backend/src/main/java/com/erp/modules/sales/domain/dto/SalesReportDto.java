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
        String                  generatedAt) {
}
