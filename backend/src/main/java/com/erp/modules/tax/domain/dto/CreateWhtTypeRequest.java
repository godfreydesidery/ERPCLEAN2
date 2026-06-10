package com.erp.modules.tax.domain.dto;

import com.erp.modules.tax.domain.enums.WhtKind;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request to create a WHT rate/type (ADR-0017 D-2d).
 */
public record CreateWhtTypeRequest(
        @NotBlank String companyUid,
        @NotBlank String code,
        @NotBlank String name,
        @NotNull WhtKind kind,
        @NotNull @Min(0) BigDecimal ratePct
) {}
