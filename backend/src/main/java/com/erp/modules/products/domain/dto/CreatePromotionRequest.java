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
        short priority,
        // --- P2 D5 targeting + guardrails (ADR-0041 D5) — all optional ---
        /** When set, restricts the promotion to a single customer (resolved to customer id). */
        String targetCustomerUid,
        /** When set, restricts the promotion to a single branch (resolved to branch id). */
        String targetBranchUid,
        BigDecimal minThreshold,
        Integer usageLimit,
        String couponCode,
        Boolean combinable
) {
    /**
     * Backward-compatible constructor for callers that predate the P2 D5 targeting/guardrail fields.
     * Defaults all D5 fields to null (entity defaults kept), so no existing call site changes.
     */
    public CreatePromotionRequest(
            String companyUid, String code, String name, PromotionTarget target,
            String targetProductUid, String targetCategory, PromotionEffect effect,
            BigDecimal effectValue, LocalDate effectiveFrom, LocalDate effectiveTo, short priority) {
        this(companyUid, code, name, target, targetProductUid, targetCategory, effect, effectValue,
                effectiveFrom, effectiveTo, priority,
                null, null, null, null, null, null);
    }
}
