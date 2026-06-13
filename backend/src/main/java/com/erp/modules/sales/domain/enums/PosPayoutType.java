package com.erp.modules.sales.domain.enums;

/**
 * Type of a POS session payout (ADR-0029 D-2).
 * REFUND   = cash paid out on a POS refund (cash returns to customer).
 * PAID_OUT = misc cash payout — drawer-to-safe drop / petty payout.
 * Both types SUBTRACT from expected cash (payouts are outflows from the till).
 */
public enum PosPayoutType {
    REFUND,
    PAID_OUT
}
