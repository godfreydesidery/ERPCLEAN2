package com.erp.modules.gl.domain.enums;

/**
 * The source of a journal batch/entry (ADR-0013 D-2c/D-13).
 * v1 active: MANUAL, SALES, SALES_REVERSAL, OPENING_BALANCE.
 * Reserved for later increments: AR, AP, COGS, CASH, PAYROLL, DEPRECIATION.
 * The DB CHECK admits only the v1 set; widen the IN-list additively when each poster lands.
 */
public enum JournalSourceType {
    MANUAL,
    SALES,
    SALES_REVERSAL,
    OPENING_BALANCE,

    // --- RESERVED — NOT yet admitted by the DB CHECK; widen when the increment lands ---
    AR,
    AP,
    COGS,
    CASH,
    PAYROLL,
    DEPRECIATION
}
