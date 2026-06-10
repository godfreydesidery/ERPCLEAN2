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

    // --- AR increment (ADR-0014 D-6) — now admitted by the DB CHECK (V11 widens it) ---
    AR_RECEIPT,
    AR_WRITEOFF,
    AR_CREDIT_NOTE,
    // --- AP increment (ADR-0015 D-6) — now admitted by the DB CHECK (V12 widens it) ---
    AP_BILL,
    AP_PAYMENT,
    AP_DEBIT_NOTE,
    // --- Cash & Bank increment (ADR-0016 D-7) — now admitted by the DB CHECK (V13 widens it) ---
    CASH_TRANSFER,
    CASH_DIRECT,
    // --- VAT/Tax increment (ADR-0017 D-13) — now admitted by the DB CHECK (V14 widens it) ---
    VAT_RETURN,
    // --- RESERVED — NOT yet admitted by the DB CHECK; widen when the increment lands ---
    AR,
    AP,
    COGS,
    CASH,
    PAYROLL,
    DEPRECIATION
}
