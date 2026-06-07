package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.ProductType;
import com.erp.platform.common.money.MoneyDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO to update an existing Product's profile.
 * code and companyId are not updatable (BR-PROD-02/08).
 */
public record UpdateProductRequest(
        @NotBlank String name,
        String description,
        @NotNull ProductType type,
        boolean sellable,
        boolean stockable,
        @NotBlank String baseUnit,
        MoneyDto cost
) {
}
