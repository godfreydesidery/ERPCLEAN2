package com.erp.modules.sales.domain.dto;

import com.erp.modules.products.domain.enums.VatStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request DTO to manually create a new TaxRate master row (TAXRATE.MANAGE).
 *
 * <p>The rate field mirrors the DB CHECK constraint {@code chk_tax_rate_value}:
 * value must be {@code >= 0.0000} and {@code <= 0.9999} (i.e. 0% – 99.99%).
 *
 * <p>The (company, vatStatus) pair is unique — there can be at most one rate row per
 * VAT classification per company. Attempting to create a duplicate yields HTTP 409.
 */
public record CreateTaxRateRequest(
        @NotNull
        Long companyId,

        @NotNull
        VatStatus vatStatus,

        @NotNull
        @DecimalMin("0.0000")
        @DecimalMax("0.9999")
        BigDecimal rate
) {
}
