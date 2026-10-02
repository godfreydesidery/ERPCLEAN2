package com.erp.modules.ar.domain.enums;

/**
 * The kind of movement on a customer statement. Invoices and opening balances raise what the
 * customer owes (debit); receipts, credit notes and write-offs reduce it (credit); a reversed receipt
 * (a bounced cheque) puts the amount back (debit).
 */
public enum ArLedgerEntryType {
    OPENING_BALANCE,
    INVOICE,
    RECEIPT,
    RECEIPT_REVERSAL,
    CREDIT_NOTE,
    WRITE_OFF
}
