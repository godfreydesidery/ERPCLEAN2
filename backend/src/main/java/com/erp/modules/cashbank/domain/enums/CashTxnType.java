package com.erp.modules.cashbank.domain.enums;

/** Cash transaction type — maps to the originating business act (ADR-0016 D-2b). */
public enum CashTxnType {
    AR_RECEIPT,
    AP_PAYMENT,
    TRANSFER_IN,
    TRANSFER_OUT,
    DIRECT_ENTRY
}
