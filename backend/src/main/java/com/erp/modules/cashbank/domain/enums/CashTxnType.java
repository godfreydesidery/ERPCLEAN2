package com.erp.modules.cashbank.domain.enums;

/**
 * Cash transaction type — maps to the originating business act (ADR-0016 D-2b).
 *
 * <p>The last four (V106, gap review wave 3, ARC-08) put the cash side of the till into the cash
 * book. Each mirrors a GL entry the originating flow already posts — none of them posts to the GL
 * itself.
 */
public enum CashTxnType {
    AR_RECEIPT,
    AP_PAYMENT,
    TRANSFER_IN,
    TRANSFER_OUT,
    DIRECT_ENTRY,
    /** IN: a sale tender (net of change) taken into a cash/bank account. */
    SALE_TENDER,
    /** OUT: the mirror of a SALE_TENDER row when the sale is voided. */
    SALE_REFUND,
    /** OUT: cash paid out of a POS till (payout or till expense). */
    POS_PAYOUT,
    /** IN (over) or OUT (short): a POS session's counted-vs-expected difference. */
    POS_VARIANCE
}
