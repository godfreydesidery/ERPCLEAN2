package com.erp.modules.sales.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.List;

/** The restaurant orders not yet paid, as of {@code generatedAt}. */
public record RestaurantPendingBillsReportDto(
        ReportCompanyHeaderDto            company,
        String                            waiterName,
        String                            currency,
        List<RestaurantPendingBillRowDto> rows,
        long                              openCount,
        long                              billedCount,
        BigDecimal                        billedAmount,
        String                            generatedAt) {}
