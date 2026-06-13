package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.PurchaseSettings;
import java.math.BigDecimal;

public record PurchaseSettingsDto(
        Long   id,
        String uid,
        Long   companyId,
        boolean poApprovalEnabled,
        BigDecimal poApprovalThresholdAmount,
        String currency
) {
    public static PurchaseSettingsDto from(PurchaseSettings s) {
        return new PurchaseSettingsDto(
                s.getId(), s.getUid(), s.getCompanyId(),
                s.isPoApprovalEnabled(), s.getPoApprovalThresholdAmount(), s.getCurrency());
    }
}
