package com.erp.modules.sales.domain.enums;

/**
 * Per-company policy for a sale line priced AT OR BELOW its moving-average cost
 * ({@code sales_settings.below_cost_action}, V93, owner decision 2026-08-01).
 *
 * <p>Enforced by {@code BelowCostGuard} at sales-invoice finalise (which is also the POS path).
 * The trigger is {@code netUnitPrice <= unitCost} — "equal or less", so selling exactly at cost
 * counts. An unknown or zero cost never rejects a sale under any action.
 */
public enum BelowCostAction {

    /** No check at all — the default, and the pre-V93 behaviour. */
    OFF,

    /** Allow the sale, but record that it went out at or below cost. */
    WARN,

    /**
     * Reject unless the caller supplied the below-cost approval AND holds
     * {@code SALES.BELOW_COST.OVERRIDE} (the supervisor-approval mode).
     */
    APPROVE,

    /** Always reject — no override accepted. */
    BLOCK
}
