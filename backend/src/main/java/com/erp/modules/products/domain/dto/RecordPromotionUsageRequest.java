package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * Request to record a single promotion redemption (P2 D5, ADR-0041 D5).
 *
 * <p>Exposed by the products module so the sales side can record a redemption when it applies a
 * promotion to a sales document, with {@code usage_limit} enforced at record time. The promotion is
 * addressed by uid; {@code customerUid} is optional (anonymous/walk-in); {@code orderUid} and
 * {@code amount} capture the document the promotion was applied to and the discount granted.
 */
public record RecordPromotionUsageRequest(
        @NotBlank String promotionUid,
        String customerUid,
        String orderUid,
        BigDecimal amount
) {
}
