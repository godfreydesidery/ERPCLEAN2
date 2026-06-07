package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request DTO to add a bulk pack to a product (FR-PROD-06, UoM cutover).
 * {@code unitUid} references a UnitOfMeasure uid scoped to the product's company.
 */
public record CreateBulkPackRequest(
        @NotBlank String unitUid,
        @NotNull @DecimalMin(value = "0.000001", message = "factorToBase must be greater than zero (BR-PROD-03)")
        BigDecimal factorToBase
) {
}
