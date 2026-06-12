package com.erp.modules.purchases.domain.enums;

/** Purchase Requisition lifecycle states (ADR-0027 D-2). */
public enum RequisitionStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    REJECTED,
    CONVERTED,
    CANCELLED
}
