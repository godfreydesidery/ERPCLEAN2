package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.domain.enums.BelowCostAction;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;

/**
 * Response DTO for the per-company SO approval threshold configuration (deferred item D-4).
 * Mirrors {@code PurchaseSettingsDto}.
 */
public record SalesSettingsDto(
        Long   id,
        String uid,
        Long   companyId,
        boolean soApprovalEnabled,
        BigDecimal soApprovalThresholdAmount,
        String currency,
        /** Configurable "block negative stock on sale" (owner decision 2026-07-05, V87). */
        boolean allowNegativeStock,
        /** Configurable "sale at or below cost" policy (owner decision 2026-08-01, V93). */
        BelowCostAction belowCostAction
) {
    public static SalesSettingsDto from(SalesSettings s) {
        return new SalesSettingsDto(
                s.getId(), s.getUid(), s.getCompanyId(),
                s.isSoApprovalEnabled(), s.getSoApprovalThresholdAmount(),
                CurrencyCode.value(s.getCurrency()),
                s.isAllowNegativeStock(),
                s.getBelowCostAction());
    }
}
