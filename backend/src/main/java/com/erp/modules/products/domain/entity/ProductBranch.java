package com.erp.modules.products.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Junction table: a product is associated with one or more branches (FR-PROD-20/21, ADR-0007 D-4).
 * No uid, no status, no version — a pure existence link per DATA-MODEL junction convention
 * (mirrors customer_branch). Addressed via product uid + branch uid in the API.
 */
@Getter
@Entity
@Table(name = "product_branch")
public class ProductBranch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    // -------------------------------------------------------------------------
    // P3 — branch-level overrides (simple association columns; richer model deferred).
    // -------------------------------------------------------------------------

    /** P3: product enabled/active at this branch. Defaults to true. */
    @Column(name = "active", nullable = false)
    @Setter
    private boolean active = true;

    /** P3: branch-specific reorder level. Null = inherit the product-level value. */
    @Column(name = "reorder_level", precision = 19, scale = 6)
    @Setter
    private BigDecimal reorderLevel;

    /** P3: branch-specific selling price override. Null = inherit. */
    @Column(name = "branch_price", precision = 19, scale = 4)
    @Setter
    private BigDecimal branchPrice;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt = Instant.now();

    @Column(name = "assigned_by", nullable = false, updatable = false)
    private Long assignedBy;

    protected ProductBranch() {
        // JPA
    }

    public ProductBranch(Product product, Long branchId, Long assignedBy) {
        this.product = product;
        this.branchId = branchId;
        this.assignedBy = assignedBy;
    }
}
