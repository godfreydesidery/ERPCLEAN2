package com.erp.modules.purchases.domain.enums;

/** RFQ lifecycle states (ADR-0027 D-2). */
public enum RfqStatus {
    DRAFT,
    SENT,
    QUOTES_RECEIVED,
    AWARDED,
    CANCELLED
}
