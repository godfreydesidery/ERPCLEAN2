package com.erp.modules.gl.domain.enums;

/**
 * Posting-role keys for the {@code gl_configs} account mapping (ADR-0013 D-2d/D-5/D-13).
 * v1 active (required for sales auto-posting): SALES_REVENUE, VAT_PAYABLE, ACCOUNTS_RECEIVABLE, CASH.
 * Reserved — seeded but not yet posted-to: INVENTORY, COGS, ACCOUNTS_PAYABLE.
 */
public enum GlConfigKey {
    SALES_REVENUE,
    VAT_PAYABLE,
    ACCOUNTS_RECEIVABLE,
    CASH,
    // --- RESERVED --- seeded in v1, posted-to when the increment lands ---
    INVENTORY,
    COGS,
    ACCOUNTS_PAYABLE,
    // --- AR increment (ADR-0014 D-6/D-13) ---
    BAD_DEBT_EXPENSE,
    OPENING_BALANCE_EQUITY,
    // --- AP increment (ADR-0015 D-6/D-13) ---
    PURCHASES,
    // --- VAT/Tax increment (ADR-0017 D-5) ---
    VAT_INPUT,
    VAT_DUE,
    WHT_PAYABLE,
    WHT_RECEIVABLE,
    // --- Year-End Close increment (ADR-0019 D-9) ---
    RETAINED_EARNINGS,
    // --- Inventory Valuation & COGS increment (ADR-0020 D-8) ---
    GRNI,
    STOCK_ADJUSTMENT,
    // --- sales-depth (ADR-0029 D-4) — POS over/short variance accounts ---
    POS_CASH_OVER,
    POS_CASH_SHORT,
    /**
     * Operating expenses paid in cash out of a till drawer (V97).
     *
     * <p>Deliberately separate from {@link #POS_CASH_SHORT}: that is a VARIANCE account whose only
     * job is to make drawer discrepancies visible, so booking routine spend into it destroys the
     * control signal AND misclassifies operating expense by nature on the P&amp;L. Provisioned per
     * company by application code (never seeded in SQL), and never silently defaulted back to the
     * shortage account — an unmapped company is refused, not mis-posted.
     */
    POS_TILL_EXPENSE,
    // --- Fixed Assets increment (ADR-0030 D-3) ---
    FIXED_ASSETS,
    FIXED_ASSET_CLEARING,
    ACCUMULATED_DEPRECIATION,
    DEPRECIATION_EXPENSE,
    GAIN_LOSS_ON_DISPOSAL,
    REVALUATION_RESERVE,
    // --- procurement-depth (ADR-0027 D-9) ---
    LANDED_COST_CLEARING,
    // --- HR & Payroll increment (ADR-0032 D-8) ---
    SALARY_EXPENSE,
    EMPLOYER_STATUTORY_EXPENSE,
    PAYE_PAYABLE,
    NSSF_PAYABLE,
    WCF_PAYABLE,
    SDL_PAYABLE,
    HESLB_PAYABLE,
    NET_WAGES_PAYABLE,
    EMPLOYEE_LOAN_RECEIVABLE,
    // --- Manufacturing / Production increment (ADR-0035 D-7) ---
    WIP_INVENTORY,
    FINISHED_GOODS,
    LABOUR_APPLIED,
    OVERHEAD_APPLIED,
    MANUFACTURING_VARIANCE,
    // --- FX / Multi-currency increment (ADR-0036 D-5/D-10, V79) ---
    REALIZED_FX_GAIN,
    REALIZED_FX_LOSS,
    UNREALIZED_FX_GAIN,
    UNREALIZED_FX_LOSS
}
