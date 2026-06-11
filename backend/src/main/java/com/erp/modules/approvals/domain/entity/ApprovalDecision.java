package com.erp.modules.approvals.domain.entity;

import com.erp.modules.approvals.domain.enums.DecisionAction;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * Append-only record of a single approver acting on a single request step (ADR-0022 D-4).
 *
 * <p>Decisions are NEVER updated or deleted — the table is append-only (NFR-APR-04). A mistaken
 * approval is corrected by the consuming module raising a fresh document, not by mutating a
 * decision. No {@code @Version}, no {@code updated_*} cols — insert-only.
 *
 * <p>Service-enforced SoD: {@code decidedBy} must not equal the request's {@code submittedBy}
 * (BR-APR-06 / D-8).
 */
@Getter
@Entity
@Table(name = "approval_decisions")
public class ApprovalDecision extends UidEntity {

    @Column(name = "approval_request_id", nullable = false, updatable = false)
    private Long approvalRequestId;

    @Column(name = "approval_request_step_id", nullable = false, updatable = false)
    private Long approvalRequestStepId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", updatable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 10, updatable = false)
    private DecisionAction action;

    @Column(name = "decided_by", nullable = false, updatable = false)
    private Long decidedBy;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt = Instant.now();

    @Column(name = "comment", length = 1000, updatable = false)
    private String comment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected ApprovalDecision() {
        // JPA
    }

    public ApprovalDecision(Long approvalRequestId,
                            Long approvalRequestStepId,
                            Long companyId,
                            Long branchId,
                            DecisionAction action,
                            Long decidedBy,
                            String comment,
                            Long createdBy) {
        this.approvalRequestId     = approvalRequestId;
        this.approvalRequestStepId = approvalRequestStepId;
        this.companyId             = companyId;
        this.branchId              = branchId;
        this.action                = action;
        this.decidedBy             = decidedBy;
        this.comment               = comment;
        this.createdBy             = createdBy;
    }
}
