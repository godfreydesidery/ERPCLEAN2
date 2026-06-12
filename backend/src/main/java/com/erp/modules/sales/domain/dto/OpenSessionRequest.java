package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request to open a new POS session on a till (ADR-0029 D-5).
 */
public record OpenSessionRequest(
        @NotBlank String tillUid,
        @NotNull @DecimalMin("0.00") BigDecimal openingFloatAmount
) {}
