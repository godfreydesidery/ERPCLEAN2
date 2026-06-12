package com.erp.modules.hr.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

public record CreatePayrollRunRequest(
        @Min(1) @Max(12) short periodMonth,
        short periodYear,
        @NotNull LocalDate payDate,
        Long branchId
) {}
