package com.erp.modules.iam.domain.entity;

import com.erp.modules.iam.domain.enums.BranchType;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A physical location under a company (DATA-MODEL §1.3). Smallest unit at which stock and a
 * business day exist. {@code code} is unique within the company; at most one branch per company is
 * the default ({@code is_default}, enforced by a partial unique index).
 *
 * <p>Note on {@code isDefault}: the field is named {@code isDefault} (boolean). Lombok strips the
 * {@code is} prefix, producing {@code isDefault()} / {@code setDefault()} — matching the original
 * hand-written accessors exactly.
 */
@Getter
@Entity
@Table(name = "branches")
public class Branch extends UidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "code", nullable = false, length = 20)
    @Setter
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    @Setter
    private String name;

    @Column(name = "time_zone", nullable = false, length = 64)
    @Setter
    private String timeZone = "Africa/Dar_es_Salaam";

    @Column(name = "is_default", nullable = false)
    @Setter
    private boolean isDefault = false;

    /** P2: soft-FK to app_users/employee — scalar, no @ManyToOne (cross-module convention). */
    @Column(name = "manager_id")
    @Setter
    private Long managerId;

    /** P2: typed branch classification (nullable, descriptive only in v1). */
    @Enumerated(EnumType.STRING)
    @Column(name = "branch_type", length = 20)
    @Setter
    private BranchType branchType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    /**
     * F8 (ADR-0004 D-8): a branch is usable for a session only when both the branch itself and its
     * parent company are ACTIVE. A branch under an ARCHIVED company must not scope a login or an
     * override — callers land read-only until an admin assigns a live default.
     */
    public boolean isUsableForSession() {
        return this.status == MasterStatus.ACTIVE
                && this.company.getStatus() == MasterStatus.ACTIVE;
    }

    protected Branch() {
        // JPA
    }

    public Branch(Company company, String code, String name) {
        this.company = company;
        this.code = code;
        this.name = name;
    }
}
