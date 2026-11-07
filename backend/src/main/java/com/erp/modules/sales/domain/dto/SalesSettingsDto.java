package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.domain.enums.BelowCostAction;
import com.erp.modules.sales.domain.enums.DiscountApprovalAction;
import com.erp.modules.sales.domain.enums.PosSessionLimitMode;
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
        BigDecimal maxDiscountPercent,
        /** How this company limits the age of an open till session (V113). */
        PosSessionLimitMode posSessionLimitMode,
        /** The company's own limit in hours — set only when the mode is {@code HOURS}, else null. */
        Integer posSessionMaxOpenHours,
        /**
         * The limit the till actually enforces for this company, in hours. {@code 0} = no limit.
         * Read-only — lets the screen show the real number behind "use the server setting".
         */
        int posSessionMaxOpenHoursEffective,
        /**
         * The server setting in hours ({@code 0} = no limit), whatever this company chose. Read-only
         * — lets the screen label "use the server setting" with its real number before saving.
         */
        int posSessionServerDefaultHours
) {
    /**
     * @param effectiveSessionHours the enforced session limit, from
     *     {@code PosSessionAgePolicy.effectiveHours}
     * @param serverDefaultHours the server setting, from {@code PosSessionAgePolicy.serverDefaultHours}
     */
    public static SalesSettingsDto from(SalesSettings s, int effectiveSessionHours,
                                        int serverDefaultHours) {
        return new SalesSettingsDto(
                s.getId(), s.getUid(), s.getCompanyId(),
                s.isSoApprovalEnabled(), s.getSoApprovalThresholdAmount(),
                CurrencyCode.value(s.getCurrency()),
                s.isAllowNegativeStock(),
                s.getBelowCostAction(),
                s.getDiscountApprovalAction(),
                s.getMaxDiscountPercent(),
                modeOf(s.getPosSessionMaxOpenHours()),
                hoursOf(s.getPosSessionMaxOpenHours()),
                effectiveSessionHours,
                serverDefaultHours);
    }

    /** V113 storage: NULL = server setting, 0 = no limit, anything else = own hours. */
    public static PosSessionLimitMode modeOf(Integer stored) {
        if (stored == null) {
            return PosSessionLimitMode.SERVER_SETTING;
        }
        return stored == 0 ? PosSessionLimitMode.NO_LIMIT : PosSessionLimitMode.HOURS;
    }

    private static Integer hoursOf(Integer stored) {
        return stored == null || stored == 0 ? null : stored;
    }
}
