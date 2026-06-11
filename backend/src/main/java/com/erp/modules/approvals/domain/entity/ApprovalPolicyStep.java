package com.erp.modules.approvals.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * One ordered rung in an approval policy's step chain (ADR-0022 D-3).
 *
 * <p>Each step names an existing IAM role code as the approver reference. The code is stored as a
 * scalar string (not a FK) so the engine is decoupled from IAM's role table key — the policy-save
 * validates the code exists; a role rename is an admin operational concern (D-8, OQ-APR-role-rename).
 *
 * <p>No {@code @Version}: steps are edited only via the policy aggregate; the policy header's
 * version guards the aggregate (D-3).
 */
@Getter
@Entity
@Table(name = "approval_policy_steps")
public class ApprovalPolicyStep extends UidEntity {

    @Column(name = "approval_policy_id", nullable = false, updatable = false)
    private Long approvalPolicyId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** 1-based, dense, unique per policy (validated at save). */
    @Column(name = "sequence", nullable = false)
    @Setter
    private short sequence;

    /** IAM role code that must approve this step (e.g. {@code FINANCE_MANAGER}). */
    @Column(name = "approver_role_code", nullable = false, length = 64)
    @Setter
    private String approverRoleCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected ApprovalPolicyStep() {
        // JPA
    }

    public ApprovalPolicyStep(Long approvalPolicyId,
                              Long companyId,
                              short sequence,
                              String approverRoleCode,
                              Long createdBy) {
        this.approvalPolicyId = approvalPolicyId;
        this.companyId        = companyId;
        this.sequence         = sequence;
        this.approverRoleCode = approverRoleCode;
        this.createdBy        = createdBy;
    }
}
