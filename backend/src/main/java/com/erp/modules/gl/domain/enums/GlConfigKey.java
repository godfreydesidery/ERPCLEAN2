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
    // --- Fixed Assets increment (ADR-0030 D-3) ---
    FIXED_ASSETS,
    FIXED_ASSET_CLEARING,
    ACCUMULATED_DEPRECIATION,
    DEPRECIATION_EXPENSE,
    GAIN_LOSS_ON_DISPOSAL,
    REVALUATION_RESERVE,
    // --- procurement-depth (ADR-0027 D-9) ---
    LANDED_COST_CLEARING
}
