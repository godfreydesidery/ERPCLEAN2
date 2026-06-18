package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.StatutoryRateType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateStatutoryRateSetRequest(
        @NotNull StatutoryRateType rateType,
        @NotNull LocalDate effectiveFrom,
        @DecimalMin(value = "0.0", message = "employeeRate must be >= 0")
        @DecimalMax(value = "100.0", message = "employeeRate must be <= 100")
        BigDecimal employeeRate,
        @DecimalMin(value = "0.0", message = "employerRate must be >= 0")
        @DecimalMax(value = "100.0", message = "employerRate must be <= 100")
        BigDecimal employerRate,
        @NotBlank String basis,
        @PositiveOrZero(message = "ceilingAmount must be >= 0")
        BigDecimal ceilingAmount,
        Short headcountThreshold,
        String description
) {}
