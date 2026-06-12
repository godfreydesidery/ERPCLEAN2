package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request to create a customer-specific contract price (ADR-0029 D-6, FR-SD-10).
 */
public record CreateCustomerPriceRequest(
        @NotBlank String companyUid,
        @NotBlank String customerUid,
        @NotBlank String productUid,
        @NotNull @DecimalMin("0") BigDecimal unitPriceAmount,
        @NotBlank String currency,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {
}
