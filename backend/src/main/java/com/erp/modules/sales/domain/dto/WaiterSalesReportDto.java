package com.erp.modules.sales.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.util.List;

/**
 * Restaurant sales per waiter: the paid bills of each waiter over a date range, with a total row.
 * A waiter is whoever opened the order (ADR-0066 D-8).
 */
public record WaiterSalesReportDto(
        ReportCompanyHeaderDto  company,
        String                  fromDate,
        String                  toDate,
        String                  waiterName,
        String                  currency,
        List<WaiterSalesRowDto> rows,
        WaiterSalesRowDto       totals,
        String                  generatedAt) {}
