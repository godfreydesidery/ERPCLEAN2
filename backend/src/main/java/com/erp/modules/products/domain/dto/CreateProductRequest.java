package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.RestrictedKind;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.platform.common.money.MoneyDto;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request DTO to create a new Product.
 * Carries {@code companyUid} (String) per ADR-0007 D-12.
 * {@code baseUnitUid} references a UnitOfMeasure uid scoped to the same company (UoM cutover).
 * {@code code} is OPTIONAL: blank → the system auto-assigns PROD-#### (FR-PROD-23); a supplied
 * value is used as-is (trimmed/uppercased) and must be unique within the company (BR-PROD-08).
 * {@code vatStatus} defaults to STANDARD when null (ADR-0008 D-5a).
 * D-10 planning/sourcing fields added in ADR-0040 — all optional.
 *
 * <p>PRD-03/PRD-04: the descriptive attributes (category/department, brand, manufacturer, HS code,
 * image URL, notes) and the lot/serial/expiry tracking flags were on the Product row all along but
 * were never accepted here, so the Product Master silently discarded them. All optional; null/blank
 * leaves the column empty and a null tracking flag means "off".
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
        RestrictedKind restrictedKind,
        // PRD-03 descriptive attributes (optional)
        @Size(max = 60) String category,
        @Size(max = 120) String brand,
        @Size(max = 160) String manufacturer,
        @Size(max = 20) String hsCode,
        @Size(max = 500) String imageUrl,
        @Size(max = 1000) String notes,
        // PRD-04 tracking flags (optional; null → false)
        Boolean lotTracked,
        Boolean serialTracked,
        Boolean expiryTracked
) {

    /** Back-compatible form predating the PRD-03/PRD-04 attributes — they default to empty/off. */
    public CreateProductRequest(String companyUid, String code, String name, String description,
                                ProductType type, boolean sellable, boolean stockable,
                                String baseUnitUid, MoneyDto cost, VatStatus vatStatus,
                                BigDecimal reorderLevel, BigDecimal reorderQty,
                                BigDecimal safetyStock, BigDecimal minStock, BigDecimal maxStock,
                                Integer leadTimeDays, Boolean purchasable, Long preferredSupplierId,
                                RestrictedKind restrictedKind) {
        this(companyUid, code, name, description, type, sellable, stockable, baseUnitUid, cost,
                vatStatus, reorderLevel, reorderQty, safetyStock, minStock, maxStock, leadTimeDays,
                purchasable, preferredSupplierId, restrictedKind,
                null, null, null, null, null, null, null, null, null);
    }
}
