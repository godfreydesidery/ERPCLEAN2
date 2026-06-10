package com.erp.modules.tax.domain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request to update a WHT rate/type (ADR-0017 D-2d).
 */
public record UpdateWhtTypeRequest(
        @NotBlank String name,
        @NotNull @Min(0) BigDecimal ratePct,
        @NotNull Boolean active
) {}
