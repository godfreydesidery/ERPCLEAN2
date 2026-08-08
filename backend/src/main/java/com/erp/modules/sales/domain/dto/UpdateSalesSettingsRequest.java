package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.BelowCostAction;
import com.erp.modules.sales.domain.enums.DiscountApprovalAction;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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
         * {@code null} means "leave the stored stance alone" — see the note in
         * {@code SalesSettingsServiceImpl.update} for why absent must never mean OFF.
         */
        BelowCostAction belowCostAction,
        /**
         * Configurable "manager-authorised discount" stance (K7, V95). Omitted / {@code null} means
         * "leave the stored policy alone", and takes {@code maxDiscountPercent} with it: the stance
         * and its ceiling are ONE policy and are applied together, so a client that predates K7
         * (the Sales Settings screen before this field, or an integration) cannot half-write it.
         */
        DiscountApprovalAction discountApprovalAction,
        /**
         * The discount percent a cashier may apply unaided. Applied only when
         * {@code discountApprovalAction} is present — sending it alone is meaningless, since a
         * ceiling without a stance enforces nothing. {@code null} alongside a present stance is a
         * real value meaning "no ceiling configured", which the guard reads as ZERO: every discount
         * then needs a manager. Bounds mirror the V95 CHECK constraint exactly (0–100, NUMERIC(5,2)),
         * so a bad number is refused with a field error instead of a database violation.
         */
        @DecimalMin(value = "0", message = "The maximum discount must be between 0 and 100 percent.")
        @DecimalMax(value = "100", message = "The maximum discount must be between 0 and 100 percent.")
        @Digits(integer = 3, fraction = 2,
                message = "The maximum discount may have at most two decimal places.")
        BigDecimal maxDiscountPercent
) {

    /** Back-compat: callers that predate the below-cost policy (V93) leave it untouched. */
    public UpdateSalesSettingsRequest(String companyUid, boolean soApprovalEnabled,
                                      BigDecimal soApprovalThresholdAmount, String currency,
                                      boolean allowNegativeStock) {
        this(companyUid, soApprovalEnabled, soApprovalThresholdAmount, currency,
                allowNegativeStock, null);
    }

    /** Back-compat: callers that predate the discount policy (K7, V95) leave it untouched. */
    public UpdateSalesSettingsRequest(String companyUid, boolean soApprovalEnabled,
                                      BigDecimal soApprovalThresholdAmount, String currency,
                                      boolean allowNegativeStock, BelowCostAction belowCostAction) {
        this(companyUid, soApprovalEnabled, soApprovalThresholdAmount, currency,
                allowNegativeStock, belowCostAction, null, null);
    }
}
