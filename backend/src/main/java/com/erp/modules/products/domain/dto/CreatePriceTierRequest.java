package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request to create a quantity-break price tier (ADR-0029 D-6, FR-SD-09).
 */
public record CreatePriceTierRequest(
        @NotBlank String companyUid,
        @NotBlank String productUid,
        @NotBlank String priceListUid,
        @NotNull @DecimalMin("0.000001") BigDecimal minQty,
        @NotNull @DecimalMin("0") BigDecimal unitPriceAmount,
        @NotBlank String currency
) {
}
