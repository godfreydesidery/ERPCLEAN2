package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.BelowCostAction;
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
        boolean allowNegativeStock,
        /**
         * Configurable "sale at or below cost" policy (owner decision 2026-08-01, V93). Omitted /
         * {@code null} resolves to {@link BelowCostAction#OFF} — the same value every other layer
         * reports for an unset policy, so a client that does not know about this field cannot end
         * up with a stance nobody chose.
         */
        BelowCostAction belowCostAction
) {

    /** Back-compat: callers that predate the below-cost policy (V93) leave it OFF. */
    public UpdateSalesSettingsRequest(String companyUid, boolean soApprovalEnabled,
                                      BigDecimal soApprovalThresholdAmount, String currency,
                                      boolean allowNegativeStock) {
        this(companyUid, soApprovalEnabled, soApprovalThresholdAmount, currency,
                allowNegativeStock, null);
    }
}
