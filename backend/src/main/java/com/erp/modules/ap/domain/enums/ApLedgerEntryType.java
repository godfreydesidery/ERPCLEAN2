package com.erp.modules.ap.domain.enums;

/**
 * The kind of movement on a supplier statement. Bills and opening balances raise what we owe the
 * supplier (credit); payments and debit notes reduce it (debit); a reversed payment (a bounced
 * cheque) puts the amount back (credit).
 */
public enum ApLedgerEntryType {
    OPENING_BALANCE,
    BILL,
    PAYMENT,
    PAYMENT_REVERSAL,
    DEBIT_NOTE
}
