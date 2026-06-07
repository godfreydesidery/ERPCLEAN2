package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request DTO to update the rate on a TaxRate master row (ADR-0008 D-5b, TAXRATE.MANAGE).
 * Rate must be >= 0 and < 1 (mirrors DB CHECK chk_tax_rate_value).
 */
public record UpdateTaxRateRequest(
        @NotNull
        @DecimalMin("0.0000")
        @DecimalMax("0.9999")
        BigDecimal rate
) {
}
