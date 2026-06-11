package com.erp.modules.approvals.domain.entity;

import com.erp.modules.approvals.domain.enums.ApprovalStepStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A frozen snapshot of one policy step, materialised onto a request at submit time (ADR-0022 D-5).
 *
 * <p>Copying policy steps onto the request means a later policy edit never retroactively changes
 * an in-flight governance instance (BR-APR-05). The current open step is the lowest-sequence PENDING
 * step (the only step on which a decision may be made — BR-APR-04).
 *
 * <p>No {@code @Version}: the request header's {@code @Version} guards the step-advance race
 * (NFR-APR-05); the step itself has no independent version.
 */
@Getter
@Entity
@Table(name = "approval_request_steps")
public class ApprovalRequestStep extends UidEntity {

    @Column(name = "approval_request_id", nullable = false, updatable = false)
    private Long approvalRequestId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", updatable = false)
    private Long branchId;

    /** Snapshotted from the policy step at submit time (BR-APR-05). */
    @Column(name = "sequence", nullable = false, updatable = false)
    private short sequence;

    /** Snapshotted approver role code — immutable after creation. */
    @Column(name = "approver_role_code", nullable = false, length = 64, updatable = false)
    private String approverRoleCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private ApprovalStepStatus status = ApprovalStepStatus.PENDING;

    @Column(name = "resolved_by")
    @Setter
    private Long resolvedBy;

    @Column(name = "resolved_at")
    @Setter
    private Instant resolvedAt;

    /** FK to the decision that closed this step (for audit trace). */
    @Column(name = "resolving_decision_id")
    @Setter
    private Long resolvingDecisionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected ApprovalRequestStep() {
        // JPA
    }

    public ApprovalRequestStep(Long approvalRequestId,
                               Long companyId,
                               Long branchId,
                               short sequence,
                               String approverRoleCode,
                               Long createdBy) {
        this.approvalRequestId = approvalRequestId;
        this.companyId         = companyId;
        this.branchId          = branchId;
        this.sequence          = sequence;
        this.approverRoleCode  = approverRoleCode;
        this.createdBy         = createdBy;
    }
}
