package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.SalesSettings;
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
        String currency
) {
    public static SalesSettingsDto from(SalesSettings s) {
        return new SalesSettingsDto(
                s.getId(), s.getUid(), s.getCompanyId(),
                s.isSoApprovalEnabled(), s.getSoApprovalThresholdAmount(),
                CurrencyCode.value(s.getCurrency()));
    }
}
