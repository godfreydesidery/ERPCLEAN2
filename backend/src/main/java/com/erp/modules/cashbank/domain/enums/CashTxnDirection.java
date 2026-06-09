package com.erp.modules.cashbank.domain.enums;

/**
 * Cash transaction direction (ADR-0016 D-3).
 * The cash-book analogue of GL's debit/credit columns — explicit, not a signed amount.
 */
public enum CashTxnDirection {
    IN,
    OUT
}
