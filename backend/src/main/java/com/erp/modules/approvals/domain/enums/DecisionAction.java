package com.erp.modules.approvals.domain.enums;

/**
 * The two actions an approver may take on the current open step (ADR-0022 D-2, FR-APR-07/08).
 * Decisions are append-only; this enum is the stored action discriminator.
 */
public enum DecisionAction {
    APPROVE,
    REJECT
}
