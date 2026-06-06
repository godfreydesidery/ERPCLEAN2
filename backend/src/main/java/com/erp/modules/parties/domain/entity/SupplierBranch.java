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
 * Junction: supplier ↔ branch association (ADR-0006 D-4).
 */
@Getter
@Entity
@Table(name = "supplier_branch")
public class SupplierBranch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @Column(name = "assigned_by", nullable = false)
    private Long assignedBy;

    protected SupplierBranch() {
        // JPA
    }

    public SupplierBranch(Supplier supplier, Long branchId, Long assignedBy) {
        this.supplier = supplier;
        this.branchId = branchId;
        this.assignedBy = assignedBy;
        this.assignedAt = Instant.now();
    }
}
