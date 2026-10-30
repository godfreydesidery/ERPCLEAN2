package com.erp.modules.sales.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * Every restaurant bill paid over a date range. {@code totalAmount} counts only receipts still
 * standing (a voided receipt is listed, marked VOID, and left out of the total).
 */
public record RestaurantBillsReportDto(
        ReportCompanyHeaderDto     company,
        String                     fromDate,
        String                     toDate,
        String                     waiterName,
        String                     currency,
        List<RestaurantBillRowDto> rows,
        long                       billCount,
        BigDecimal                 totalAmount,
        String                     generatedAt) {}
