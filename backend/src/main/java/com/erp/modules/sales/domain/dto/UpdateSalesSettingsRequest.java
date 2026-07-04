package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * Upsert request for the per-company SO approval threshold configuration (deferred item D-4).
 * Mirrors {@code UpdatePurchaseSettingsRequest}.
 */
public record UpdateSalesSettingsRequest(
        @NotBlank String companyUid,
        boolean soApprovalEnabled,
        @PositiveOrZero BigDecimal soApprovalThresholdAmount,
        String currency
) {}
