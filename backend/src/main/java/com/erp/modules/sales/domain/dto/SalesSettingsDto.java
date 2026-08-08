package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.domain.enums.BelowCostAction;
import com.erp.modules.sales.domain.enums.DiscountApprovalAction;
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
        BelowCostAction belowCostAction,
        /** Configurable "manager-authorised discount" stance (K7, V95). OFF unless opted in. */
        DiscountApprovalAction discountApprovalAction,
        /**
         * The discount percent a cashier may apply unaided. {@code null} = no ceiling configured,
         * which the guard reads as ZERO once the stance is APPROVE/BLOCK. The screen must report
         * this exactly as the guard enforces it — see DiscountPolicySettingCrossLayerContractTest.
         */
        BigDecimal maxDiscountPercent
) {
    public static SalesSettingsDto from(SalesSettings s) {
        return new SalesSettingsDto(
                s.getId(), s.getUid(), s.getCompanyId(),
                s.isSoApprovalEnabled(), s.getSoApprovalThresholdAmount(),
                CurrencyCode.value(s.getCurrency()),
                s.isAllowNegativeStock(),
                s.getBelowCostAction(),
                s.getDiscountApprovalAction(),
                s.getMaxDiscountPercent());
    }
}
