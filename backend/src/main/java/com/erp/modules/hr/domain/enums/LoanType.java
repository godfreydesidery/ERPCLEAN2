package com.erp.modules.hr.domain.enums;

/** Employee loan classification (P2 D6, ADR-0041). Stored as VARCHAR via @Enumerated(STRING). */
public enum LoanType {
    LOAN,
    SALARY_ADVANCE,
    EMERGENCY,
    OTHER
}
