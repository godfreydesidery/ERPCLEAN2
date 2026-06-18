package com.erp.modules.cashbank.domain.enums;

/** Direction of a cheque in the register (ADR-0040 D-9). */
public enum ChequeDirection {
    /** Cheque issued by the company to a supplier (outgoing payment — AP side). */
    OUTBOUND,
    /** Cheque received from a customer (incoming — AR side). */
    INBOUND
}
