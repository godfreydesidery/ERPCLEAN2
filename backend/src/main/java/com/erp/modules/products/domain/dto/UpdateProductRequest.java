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
import java.util.List;

/**
 * Request DTO to update an existing Product's profile.
 * code and companyId are not updatable (BR-PROD-02/08).
 * {@code baseUnitUid} references a UnitOfMeasure uid scoped to the same company (UoM cutover).
 * {@code vatStatus} defaults to STANDARD when null (ADR-0008 D-5a).
 * D-10 planning/sourcing fields added in ADR-0040 — all optional.
 *
 * <p><b>Null means "unchanged" (PRD-05)</b> for the planning fields (reorder level/qty, safety,
 * min, max, lead time, preferred supplier), {@code restrictedKind}, the PRD-03 descriptive
 * attributes and the PRD-04 tracking flags. A screen that does not show a field must not wipe
 * it: the detail screen used to null reorder levels, the supplier and the 18+ flag on every save.
 * To clear a planning field deliberately, name it in {@link #clearFields}; a descriptive text
 * field is cleared by sending it blank.
 */
public record UpdateProductRequest(
        @NotBlank String name,
        String description,
        @NotNull ProductType type,
        boolean sellable,
        boolean stockable,
        @NotBlank String baseUnitUid,
        MoneyDto cost,
        VatStatus vatStatus,
        // D-10 planning + sourcing (all optional; null = unchanged)
        BigDecimal reorderLevel,
        BigDecimal reorderQty,
        BigDecimal safetyStock,
        BigDecimal minStock,
        BigDecimal maxStock,
        @Min(0) Integer leadTimeDays,
        Boolean purchasable,
        Long preferredSupplierId,
        // ADR-0044 D-3a — optional; null = unchanged (send NONE to remove a restriction)
        RestrictedKind restrictedKind,
        // PRD-03 descriptive attributes (null = unchanged, blank = clear)
        @Size(max = 60) String category,
        @Size(max = 120) String brand,
        @Size(max = 160) String manufacturer,
        @Size(max = 20) String hsCode,
        @Size(max = 500) String imageUrl,
        @Size(max = 1000) String notes,
        // PRD-04 tracking flags (null = unchanged)
        Boolean lotTracked,
        Boolean serialTracked,
        Boolean expiryTracked,
        /**
         * Planning fields to clear explicitly (PRD-05): any of {@code reorderLevel},
         * {@code reorderQty}, {@code safetyStock}, {@code minStock}, {@code maxStock},
         * {@code leadTimeDays}, {@code preferredSupplierId}. Unknown names are ignored.
         */
        List<String> clearFields
) {

    /** Back-compatible form predating PRD-03/04/05 — the new attributes are left unchanged. */
    public UpdateProductRequest(String name, String description, ProductType type,
                                boolean sellable, boolean stockable, String baseUnitUid,
                                MoneyDto cost, VatStatus vatStatus, BigDecimal reorderLevel,
                                BigDecimal reorderQty, BigDecimal safetyStock, BigDecimal minStock,
                                BigDecimal maxStock, Integer leadTimeDays, Boolean purchasable,
                                Long preferredSupplierId, RestrictedKind restrictedKind) {
        this(name, description, type, sellable, stockable, baseUnitUid, cost, vatStatus,
                reorderLevel, reorderQty, safetyStock, minStock, maxStock, leadTimeDays,
                purchasable, preferredSupplierId, restrictedKind,
                null, null, null, null, null, null, null, null, null, null);
    }

    /** True when {@code field} was named in {@link #clearFields}. */
    public boolean clears(String field) {
        return clearFields != null && clearFields.contains(field);
    }
}
