package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.StatutoryRateType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateStatutoryRateSetRequest(
        @NotNull StatutoryRateType rateType,
        @NotNull LocalDate effectiveFrom,
        BigDecimal employeeRate,
        BigDecimal employerRate,
        @NotBlank String basis,
        BigDecimal ceilingAmount,
        Short headcountThreshold,
        String description
) {}
