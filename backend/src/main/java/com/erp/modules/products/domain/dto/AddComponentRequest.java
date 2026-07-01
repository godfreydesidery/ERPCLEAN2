package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Request DTO to add a component to a composed product (FR-PROD-14). */
public record AddComponentRequest(
        @NotBlank String componentProductUid,
        @NotNull @DecimalMin(value = "0.000001", message = "Quantity must be greater than zero.")
        BigDecimal quantity
) {
}
