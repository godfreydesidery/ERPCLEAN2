package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.ProductType;
import com.erp.platform.common.money.MoneyDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO to create a new Product.
 * Carries {@code companyUid} (String) per ADR-0007 D-12 — the convention-consistent choice
 * (addresses company by its external uid, not the internal numeric id).
 */
public record CreateProductRequest(
        @NotBlank String companyUid,
        @NotBlank String name,
        String description,
        @NotNull ProductType type,
        boolean sellable,
        boolean stockable,
        @NotBlank String baseUnit,
        MoneyDto cost
) {
}
