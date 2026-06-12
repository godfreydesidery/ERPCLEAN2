package com.erp.modules.hr.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateLoanRequest(
        @NotNull Long employeeId,
        @NotNull @Positive BigDecimal principalAmount,
        @NotNull @Positive BigDecimal installmentAmount,
        @NotNull Long glAccountId,
        @NotNull LocalDate startDate,
        String currency
) {}
