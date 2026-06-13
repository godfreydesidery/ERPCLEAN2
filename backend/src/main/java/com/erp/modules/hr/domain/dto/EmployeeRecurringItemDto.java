package com.erp.modules.hr.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record EmployeeRecurringItemDto(
        Long id,
        String uid,
        Long companyId,
        Long employeeId,
        Long payComponentId,
        String payComponentName,
        BigDecimal amountOrPercent,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {}
