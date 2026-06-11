package com.erp.modules.approvals.domain.enums;

/**
 * Lifecycle states for an approval request (ADR-0022 D-2).
 *
 * <p>Terminal set = {APPROVED, REJECTED, RECALLED, CANCELLED}. A terminal request accepts no
 * further decisions, recalls, or edits (BR-APR-07). APPROVED includes both human-approved
 * (a full chain completed) and auto-approved (no policy matched, {@code auto_approved = true}).
 */
public enum ApprovalRequestStatus {
    /** In-flight: awaiting at least one step decision. */
    PENDING,
    /** All steps approved (or auto-approved when no policy matched). Terminal. */
    APPROVED,
    /** A step was rejected — the whole chain is killed. Terminal. */
    REJECTED,
    /** Submitter (or APPROVALS.ADMIN) withdrew the request. Terminal. */
    RECALLED,
    /** Admin force-cancelled a non-terminal request. Terminal. */
    CANCELLED;

    /** True for all terminal states — no further transitions are allowed. */
    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == RECALLED || this == CANCELLED;
    }
}
