package com.erp.modules.costing.domain.entity;

import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * One selectable member of a dimension — a cost centre, department, etc. (ADR-0025 D-2).
 * Referenced by the four nullable slot-columns on {@code journal_lines} (D-3) as a scalar Long id
 * — no cross-module JPA association (D-7).
 *
 * <p>{@code is_active} is the tagging gate: an inactive value is excluded from new tagging
 * (FR-CC-04) but remains on historical journal lines (BR-CC-05, append-only ledger).
 * {@code status} (MasterStatus) is the soft-delete lifecycle; the two are independent.
 *
 * <p>{@code parent_id} is a self-FK for roll-up reads (FR-CC-02/16). Service validates same
 * dimension + same company + acyclic (BR-CC-09).
 */
@Getter
@Entity
@Table(name = "dimension_values")
public class DimensionValue extends UidEntity {

    /** Denormalised from dimension for fast tenant-scope assertion (no join needed). */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** FK → dimensions(id); the owning dimension/slot. */
    @Column(name = "dimension_id", nullable = false, updatable = false)
    private Long dimensionId;

    /** User-supplied code, unique per dimension per company (uq_dimension_value_dim_code). */
    @Column(name = "code", nullable = false, length = 40)
    @Setter
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    @Setter
    private String name;

    /**
     * Self-FK for parent roll-up (FR-CC-02). Null = root value.
     * DB FK: fk_dimension_value_parent → dimension_values(id).
     * Service enforces same dimension + same company + acyclic (BR-CC-09).
     */
    @Column(name = "parent_id")
    @Setter
    private Long parentId;

    /** The tagging gate: false = excluded from new tagging; stays on historical lines. */
    @Column(name = "is_active", nullable = false)
    @Setter
    private boolean active;

    /** P3: owner/manager reference (scalar soft-FK, nullable — no cross-module JPA association, D-7). */
    @Column(name = "manager_id")
    @Setter
    private Long managerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    protected DimensionValue() {
        // JPA
    }

    public DimensionValue(Long companyId, Long dimensionId, String code, String name,
                          Long parentId, Long createdBy) {
        this.companyId   = companyId;
        this.dimensionId = dimensionId;
        this.code        = code;
        this.name        = name;
        this.parentId    = parentId;
        this.active      = true;
        this.createdBy   = createdBy;
    }
}
