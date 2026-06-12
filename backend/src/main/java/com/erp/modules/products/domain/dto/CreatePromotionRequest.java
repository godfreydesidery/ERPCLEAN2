package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.PromotionEffect;
import com.erp.modules.products.domain.enums.PromotionTarget;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request to create a promotion rule (ADR-0029 D-6, FR-SD-11).
 */
public record CreatePromotionRequest(
        @NotBlank String companyUid,
        @NotBlank String code,
        @NotBlank String name,
        @NotNull PromotionTarget target,
        /** Required when target = PRODUCT. */
        String targetProductUid,
        /** Required when target = CATEGORY. */
        String targetCategory,
        @NotNull PromotionEffect effect,
        @NotNull @DecimalMin("0") BigDecimal effectValue,
        @NotNull LocalDate effectiveFrom,
        @NotNull LocalDate effectiveTo,
        short priority
) {
}
