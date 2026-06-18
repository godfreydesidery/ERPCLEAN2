package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.PromotionUsage;
import java.math.BigDecimal;

/**
 * Response DTO for a recorded promotion redemption (P2 D5, ADR-0041 D5).
 */
public record PromotionUsageDto(
        Long id,
        String uid,
        Long companyId,
        Long promotionId,
        Long customerId,
        Long usedBy,
        String usedAt,
        String orderUid,
        BigDecimal amount
) {

    public static PromotionUsageDto from(PromotionUsage u) {
        return new PromotionUsageDto(
                u.getId(),
                u.getUid(),
                u.getCompanyId(),
                u.getPromotionId(),
                u.getCustomerId(),
                u.getUsedBy(),
                u.getUsedAt() != null ? u.getUsedAt().toString() : null,
                u.getOrderUid(),
                u.getAmount()
        );
    }
}
