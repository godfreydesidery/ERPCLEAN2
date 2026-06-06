package com.erp.modules.parties.domain.entity;

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
 * Junction: customer ↔ branch association (ADR-0006 D-4).
 * No uid on link tables per DATA-MODEL convention. Hard-delete to remove an association.
 */
@Getter
@Entity
@Table(name = "customer_branch")
public class CustomerBranch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** FK → {@code branches.id}. Loaded lazily to avoid pulling the full branch graph. */
    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by", nullable = false)
    private Long assignedBy;

    protected CustomerBranch() {
        // JPA
    }

    public CustomerBranch(Customer customer, Long branchId, Long assignedBy) {
        this.customer = customer;
        this.branchId = branchId;
        this.assignedBy = assignedBy;
        this.assignedAt = Instant.now();
    }
}
