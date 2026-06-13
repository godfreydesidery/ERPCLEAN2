package com.erp.modules.purchases.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record UpdatePurchaseSettingsRequest(
        @NotBlank String companyUid,
        boolean poApprovalEnabled,
        @PositiveOrZero BigDecimal poApprovalThresholdAmount,
        String currency
) {}
