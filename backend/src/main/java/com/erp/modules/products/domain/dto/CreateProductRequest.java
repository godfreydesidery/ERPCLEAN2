package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.platform.common.money.MoneyDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO to create a new Product.
 * Carries {@code companyUid} (String) per ADR-0007 D-12.
 * {@code baseUnitUid} references a UnitOfMeasure uid scoped to the same company (UoM cutover).
 * {@code code} is OPTIONAL: blank → the system auto-assigns PROD-#### (FR-PROD-23); a supplied
 * value is used as-is (trimmed/uppercased) and must be unique within the company (BR-PROD-08).
 * {@code vatStatus} defaults to STANDARD when null (ADR-0008 D-5a).
 */
public record CreateProductRequest(
        @NotBlank String companyUid,
        String code,
        @NotBlank String name,
        String description,
        @NotNull ProductType type,
        boolean sellable,
        boolean stockable,
        @NotBlank String baseUnitUid,
        MoneyDto cost,
        VatStatus vatStatus
) {
}
