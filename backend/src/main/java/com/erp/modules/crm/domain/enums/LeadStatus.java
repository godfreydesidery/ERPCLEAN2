package com.erp.modules.crm.domain.enums;

/** Lead lifecycle (ADR-0031 D-2). Transitions are service-guarded and audited. */
public enum LeadStatus {
    NEW,
    CONTACTED,
    QUALIFIED,
    CONVERTED,
    DISQUALIFIED
}
