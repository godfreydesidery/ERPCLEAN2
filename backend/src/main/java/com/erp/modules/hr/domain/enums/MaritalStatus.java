package com.erp.modules.hr.domain.enums;

/** Employee marital status (P2 D6, ADR-0041). Stored as VARCHAR via @Enumerated(STRING). */
public enum MaritalStatus {
    SINGLE,
    MARRIED,
    DIVORCED,
    WIDOWED,
    SEPARATED,
    OTHER
}
