package com.erp.modules.hr.domain.enums;

/**
 * A payroll statutory liability the employer pays over to an authority (ACC-07): each one is the
 * credit balance payroll posting builds on its own control account (2500-2540 by default).
 */
public enum StatutoryLiability {
    /** Pay As You Earn — TRA. */
    PAYE,
    /** NSSF employee + employer contributions. */
    NSSF,
    /** Workers Compensation Fund. */
    WCF,
    /** Skills Development Levy — TRA. */
    SDL,
    /** Higher Education Students' Loans Board deductions. */
    HESLB
}
