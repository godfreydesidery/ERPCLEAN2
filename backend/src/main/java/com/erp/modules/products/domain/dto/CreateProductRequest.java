package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.RestrictedKind;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.platform.common.money.MoneyDto;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request DTO to create a new Product.
 * Carries {@code companyUid} (String) per ADR-0007 D-12.
 * {@code baseUnitUid} references a UnitOfMeasure uid scoped to the same company (UoM cutover).
 * {@code code} is OPTIONAL: blank → the system auto-assigns PROD-#### (FR-PROD-23); a supplied
 * value is used as-is (trimmed/uppercased) and must be unique within the company (BR-PROD-08).
 * {@code vatStatus} defaults to STANDARD when null (ADR-0008 D-5a).
 * D-10 planning/sourcing fields added in ADR-0040 — all optional.
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
        VatStatus vatStatus,
        // D-10 planning + sourcing (all optional)
        BigDecimal reorderLevel,
        BigDecimal reorderQty,
        BigDecimal safetyStock,
        BigDecimal minStock,
        BigDecimal maxStock,
        @Min(0) Integer leadTimeDays,
        Boolean purchasable,
        Long preferredSupplierId,
        // ADR-0044 D-3a — optional; null → NONE (safe default, all existing callers unaffected)
        RestrictedKind restrictedKind
) {
}
