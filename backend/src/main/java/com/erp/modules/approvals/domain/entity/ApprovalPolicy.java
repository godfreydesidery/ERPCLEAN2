package com.erp.modules.approvals.domain.entity;

import com.erp.modules.approvals.domain.enums.PolicyBranchScope;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * An approval policy master row (ADR-0022 D-3).
 *
 * <p>A policy answers: for this {@code document_type}, when the submitted amount falls in the
 * half-open band {@code [min_amount, max_amount)}, and (optionally) for this branch, what
 * ordered approval chain is required? Policies are per-company; only active ones participate
 * in the match function ({@code is_active = true AND status = ACTIVE}).
 *
 * <p>Band non-overlap is enforced at save time by {@code ApprovalPolicyService} (BR-APR-02) —
 * not by a DB CHECK (a cross-row invariant). The V20 CHECKs enforce single-row sanity only.
 */
@Getter
@Entity
@Table(name = "approval_policies")
public class ApprovalPolicy extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "document_type", nullable = false, length = 60)
    @Setter
    private String documentType;

    @Column(name = "name", nullable = false, length = 160)
    @Setter
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "branch_scope", nullable = false, length = 20)
    @Setter
    private PolicyBranchScope branchScope = PolicyBranchScope.COMPANY_WIDE;

    /** Set iff {@code branchScope = BRANCH}; drives the more-specific match preference. */
    @Column(name = "branch_id")
    @Setter
    private Long branchId;

    /** Inclusive lower bound of the amount band (base currency). */
    @Column(name = "min_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal minAmount = BigDecimal.ZERO;

    /** Exclusive upper bound; NULL = unbounded (the top band). */
    @Column(name = "max_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal maxAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    /** Active flag: only active policies match new submissions. Does not affect in-flight requests. */
    @Column(name = "is_active", nullable = false)
    @Setter
    private boolean active = true;

    /** MasterStatus soft-delete lifecycle (the chart_of_accounts precedent — D-3). */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    protected ApprovalPolicy() {
        // JPA
    }

    public ApprovalPolicy(Long companyId,
                          String documentType,
                          String name,
                          PolicyBranchScope branchScope,
                          Long branchId,
                          BigDecimal minAmount,
                          BigDecimal maxAmount,
                          String currency,
                          String notes,
                          Long createdBy) {
        this.companyId    = companyId;
        this.documentType = documentType;
        this.name         = name;
        this.branchScope  = branchScope;
        this.branchId     = branchId;
        this.minAmount    = minAmount;
        this.maxAmount    = maxAmount;
        this.currency     = CurrencyCode.of(currency);
        this.notes        = notes;
        this.createdBy    = createdBy;
    }
}
