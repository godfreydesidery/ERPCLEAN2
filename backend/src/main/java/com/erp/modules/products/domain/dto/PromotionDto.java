package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.PromotionEffect;
import com.erp.modules.products.domain.enums.PromotionTarget;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response DTO for a promotion rule (ADR-0029 D-6).
 */
public record PromotionDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        String name,
        PromotionTarget target,
        Long targetProductId,
        String targetCategory,
        PromotionEffect effect,
        BigDecimal effectValue,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        short priority,
        MasterStatus status
) {
}
