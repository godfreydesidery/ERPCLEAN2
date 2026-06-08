package com.erp.modules.routes.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * Junction: route ↔ branch (N:M, ADR-0012 D-5).
 * Mirrors {@code customer_branch} exactly. No uid; no status/version. Same-company guard in service.
 * A route is company-owned and spans branches — visible only at its associated branches (FR-ROUTE-11/12).
 */
@Getter
@Entity
@Table(name = "route_branch")
public class RouteBranch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    /** FK → branches.id; scalar — no cross-module @ManyToOne into IAM entities. */
    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by", nullable = false)
    private Long assignedBy;

    protected RouteBranch() {
        // JPA
    }

    public RouteBranch(Route route, Long branchId, Long assignedBy) {
        this.route = route;
        this.branchId = branchId;
        this.assignedBy = assignedBy;
        this.assignedAt = Instant.now();
    }
}
