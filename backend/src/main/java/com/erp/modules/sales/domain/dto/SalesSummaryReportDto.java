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
        String generatedAt) {
}
