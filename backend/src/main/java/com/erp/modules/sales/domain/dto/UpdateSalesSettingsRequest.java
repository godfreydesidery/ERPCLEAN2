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
        String currency,
        /** Configurable "block negative stock on sale" (owner decision 2026-07-05, V87). */
        boolean allowNegativeStock
) {}
