package com.erp.modules.sales.domain.enums;

/**
 * Type of a POS session payout (ADR-0029 D-5).
 * CASH_IN  = float added to till.
 * CASH_OUT = expense or petty-cash removal from till.
 */
public enum PosPayoutType {
    CASH_IN,
    CASH_OUT
}
