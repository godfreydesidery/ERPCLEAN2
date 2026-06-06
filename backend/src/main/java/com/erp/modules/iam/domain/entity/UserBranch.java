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
 * A branch assignment (DATA-MODEL §1.9): binds a user to a branch with an optional default flag.
 * Exactly one row per user may have {@code isDefault = true} (enforced by
 * {@code uq_user_branch_default}, a partial unique index, ADR-0001 D-C).
 *
 * <p>The user's default <em>company</em> is derived from {@code branch.company} (ADR-0001 D-B) —
 * no separate company FK on this table.
 *
 * <p>{@code assignedAt} drives the earliest-assigned fallback when the current default is removed
 * (ADR-0001 D-D). {@code userId} and {@code assignedBy} are kept as ids (scalar FK) — the same
 * convention as {@link UserRole}, where user/actor references are not owned aggregates.
 *
 * <p>Lombok {@code @Getter} only; mutation goes through the behaviour methods {@link #markDefault()}
 * and {@link #clearDefault()} to make intent explicit at every call site.
 */
@Getter
@Entity
@Table(name = "user_branch")
public class UserBranch extends UidEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by", nullable = false)
    private Long assignedBy;

    protected UserBranch() {
        // JPA
    }

    /**
     * Creates a branch assignment. {@code isDefault} starts {@code false}; call
     * {@link #markDefault()} immediately after construction to make it the user's default.
     */
    public UserBranch(Long userId, Branch branch, Long assignedBy) {
        this.userId = userId;
        this.branch = branch;
        this.assignedBy = assignedBy;
        this.assignedAt = Instant.now();
    }

    /** Set this assignment as the user's default branch. */
    public void markDefault() {
        this.isDefault = true;
    }

    /** Clear the default flag (called before promoting another assignment to default). */
    public void clearDefault() {
        this.isDefault = false;
    }
}
