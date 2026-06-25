package com.erp.modules.iam.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * Explicit, non-authoritative user↔company membership (DATA-MODEL V77).
 *
 * <p>The membership oracle is <em>additive</em>: a user is "in" company C if they have an active
 * {@code user_role(company_id=C)} OR an active {@code user_branch(branch.company_id=C)} OR an
 * active {@code user_company(user, C)} row. A {@code user_company} row never blocks a role/branch
 * grant and never strips access — it only ADDS a member. Root bypasses everything.
 *
 * <p>Scalar {@code userId} and {@code assignedBy} follow the convention established by
 * {@link UserBranch} and {@link UserRole}: scoping references stored as IDs, not as owned
 * aggregates. Only {@code company} is a full {@code @ManyToOne} because the DTO needs company
 * attributes (uid, name) without a separate repository lookup from the service.
 *
 * <p>Lombok {@code @Getter} only; mutation goes through {@link #revoke(Instant)} to make intent
 * explicit at every call site.
 */
@Getter
@Entity
@Table(name = "user_company")
public class UserCompany extends UidEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by")
    private Long assignedBy;

    /** Soft-revoke timestamp. {@code null} = active. Mirrors {@link UserRole#revokedAt}. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected UserCompany() {
        // JPA
    }

    /**
     * Creates an active company membership. {@code uid} is assigned by {@link UidEntity#assignUid()}
     * on first persist — never set from SQL.
     *
     * @param userId     internal id of the user being assigned
     * @param company    target company aggregate
     * @param assignedBy internal id of the acting user (may be {@code null} for system-generated rows)
     */
    public UserCompany(Long userId, Company company, Long assignedBy) {
        this.userId     = userId;
        this.company    = company;
        this.assignedBy = assignedBy;
        this.assignedAt = Instant.now();
    }

    /** {@code true} while this membership has not been revoked. */
    public boolean isActive() {
        return revokedAt == null;
    }

    /**
     * Soft-revoke this membership (sets {@code revokedAt}). The row stays in the table for audit
     * purposes; the unique partial index {@code uq_user_company_active} is automatically freed so
     * a future re-assign can create a new active row for the same (user, company) pair.
     *
     * @param at revocation timestamp (usually {@code Instant.now()})
     */
    public void revoke(Instant at) {
        this.revokedAt = at;
    }
}
