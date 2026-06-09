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
    OPENING_BALANCE_EQUITY
}
