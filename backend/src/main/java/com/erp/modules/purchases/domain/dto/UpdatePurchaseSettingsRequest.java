package com.erp.modules.purchases.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record UpdatePurchaseSettingsRequest(
        @NotBlank String companyUid,
        boolean poApprovalEnabled,
        @PositiveOrZero BigDecimal poApprovalThresholdAmount,
        String currency,
        // P2 D7 — procurement policy defaults (all optional; null = leave unchanged)
        Long       defaultPaymentTermsId,
        Long       defaultLocationId,
        @PositiveOrZero BigDecimal matchTolerancePct,
        @PositiveOrZero BigDecimal matchToleranceAbs,
        Boolean    autoCloseEnabled,
        Boolean    requisitionApprovalEnabled,
        @PositiveOrZero BigDecimal requisitionApprovalThresholdAmount,
        // Saidi #4 — goods-receipt over-receipt tolerance percent (null clears / strict)
        @PositiveOrZero BigDecimal receiptTolerancePct
) {}
