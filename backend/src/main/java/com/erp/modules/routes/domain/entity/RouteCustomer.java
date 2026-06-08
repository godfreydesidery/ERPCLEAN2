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
 * Junction: route ↔ customer (N:M, ADR-0012 D-3).
 * No uid; no status/version — same shape as {@code customer_branch}. Hard-delete removes the link.
 * Same-company guard enforced at service layer by {@link com.erp.modules.routes.service.RouteAssignmentGuard}.
 */
@Getter
@Entity
@Table(name = "route_customer")
public class RouteCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    /** FK → customers.id; scalar — no cross-module @ManyToOne into parties entities. */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by", nullable = false)
    private Long assignedBy;

    protected RouteCustomer() {
        // JPA
    }

    public RouteCustomer(Route route, Long customerId, Long assignedBy) {
        this.route = route;
        this.customerId = customerId;
        this.assignedBy = assignedBy;
        this.assignedAt = Instant.now();
    }
}
