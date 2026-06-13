package com.erp.modules.hr.domain.enums;

/** Payroll run lifecycle status (ADR-0032 D-2). */
public enum PayrollRunStatus {
    DRAFT,
    CALCULATED,
    APPROVED,
    POSTED,
    PAID,
    REVERSED
}
