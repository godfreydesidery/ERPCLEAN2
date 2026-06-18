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
 * A role assignment (DATA-MODEL §1.8): binds a {@link Role} to a user, scoped to a company and
 * optionally one branch ({@code branchId} NULL = all branches in the company, FR-IAM-13).
 * {@code revokedAt} NULL = active. The role is a {@link ManyToOne} (the resolver walks role →
 * permissions); user/company/branch/grantedBy are kept as ids — scoping references, not owned
 * aggregates (same convention as {@code UserBranch.userId}/{@code assignedBy}).
 *
 * <p>Lombok generates getters only (PROJECT-CONVENTIONS §3.7) — the scoping fields are immutable
 * after the grant; the only legal mutation is {@link #revoke}, kept explicit so revocation can't be
 * applied as a plain setter.
 */
@Entity
@Table(name = "user_role")
@Getter
public class UserRole extends UidEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt = Instant.now();

    @Column(name = "granted_by", nullable = false)
    private Long grantedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Optional expiry for time-limited grants (P3). NULL = no expiry. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    protected UserRole() {
        // JPA
    }

    public UserRole(Long userId, Role role, Long companyId, Long branchId, Long grantedBy) {
        this.userId = userId;
        this.role = role;
        this.companyId = companyId;
        this.branchId = branchId;
        this.grantedBy = grantedBy;
        this.grantedAt = Instant.now();
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    /** True if a (non-NULL) expiry has passed at {@code now} (P3). */
    public boolean isExpired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    /** Set the optional grant expiry (P3). */
    public void setExpiresAt(Instant at) {
        this.expiresAt = at;
    }

    public void revoke(Instant now) {
        this.revokedAt = now;
    }
}
