package com.erp.modules.cashbank.domain.enums;

/**
 * Cheque lifecycle status (ADR-0016 D-5).
 * DEPOSITED and BOUNCED are added for inbound cheques (ADR-0040 D-9).
 */
public enum ChequeStatus {
    /** Cheque has been issued (outbound) or registered (inbound); not yet presented / deposited. */
    ISSUED,
    /** Outbound cheque has cleared the bank; inbound cheque proceeds confirmed. */
    CLEARED,
    /** Cheque voided before presentation. */
    CANCELLED,
    /** Inbound cheque deposited to the bank account (intermediate state before clear or bounce). */
    DEPOSITED,
    /** Inbound cheque returned unpaid by the presenting bank. */
    BOUNCED
}
