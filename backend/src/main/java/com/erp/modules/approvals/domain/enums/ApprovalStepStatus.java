package com.erp.modules.approvals.domain.enums;

/**
 * Lifecycle states for a single request step (ADR-0022 D-2).
 *
 * <p>The current open step is the lowest-sequence PENDING step. A decision is allowed only on that
 * step (BR-APR-04). SKIPPED is set on steps that were never reached because a prior step was
 * rejected (one reject kills the chain — OQ-APR-04 default).
 */
public enum ApprovalStepStatus {
    PENDING,
    APPROVED,
    REJECTED,
    SKIPPED
}
