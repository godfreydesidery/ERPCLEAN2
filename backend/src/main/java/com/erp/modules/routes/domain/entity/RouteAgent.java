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
import lombok.Setter;

/**
 * Junction: route ↔ EXTERNAL agent (N:M, ADR-0012 D-4).
 * EXTERNAL-only guard enforced at service layer. {@code isPrimary} flag drives the invoice route
 * default (FR-ROUTE-13). The DB partial unique {@code uq_route_agent_primary (agent_id) WHERE
 * is_primary} makes at-most-one-primary-per-agent a DB fact (BR-ROUTE-04).
 */
@Getter
@Entity
@Table(name = "route_agent")
public class RouteAgent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    /** FK → agents.id; scalar — no cross-module @ManyToOne. Must be an EXTERNAL agent. */
    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    /**
     * Advisory primary flag: at most one per agent, enforced by the DB partial unique.
     * Drives the invoice-route default (FR-ROUTE-13/D-6b). Non-exclusive — other assigned
     * agents still cover the route.
     */
    @Column(name = "is_primary", nullable = false)
    @Setter
    private boolean isPrimary;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by", nullable = false)
    private Long assignedBy;

    /** Soft-unassign flag (P3). A re-add is allowed once an existing link is deactivated. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "unassigned_at")
    private Instant unassignedAt;

    protected RouteAgent() {
        // JPA
    }

    public RouteAgent(Route route, Long agentId, boolean isPrimary, Long assignedBy) {
        this.route = route;
        this.agentId = agentId;
        this.isPrimary = isPrimary;
        this.assignedBy = assignedBy;
        this.assignedAt = Instant.now();
    }

    /** Soft-unassign this link (P3): clears active and stamps the unassignment time. */
    public void unassign(Instant at) {
        this.active = false;
        this.unassignedAt = at;
    }
}
