package com.erp.modules.purchases.domain.enums;

/**
 * PO approval-gate status (ADR-0027 D-6).
 * Stored on purchase_orders.approval_status.
 * Default NOT_REQUIRED (below threshold or gate disabled).
 */
public enum PoApprovalStatus {
    NOT_REQUIRED,
    PENDING,
    APPROVED,
    REJECTED
}
