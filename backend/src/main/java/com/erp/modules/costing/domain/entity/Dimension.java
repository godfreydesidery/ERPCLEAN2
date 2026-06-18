package com.erp.modules.costing.domain.entity;

import com.erp.modules.costing.domain.enums.DimensionSlot;
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
 * A dimension type per company (ADR-0025 D-2). Each row defines one analysis axis:
 * Cost Centre, Department, or a reserved slot. v1 seeds COST_CENTRE + DEPARTMENT as
 * built-ins ({@code is_built_in=true}, undeletable — FR-CC-01).
 *
 * <p>{@code slot} maps 1:1 to a {@code journal_lines.*_value_id} nullable column (D-3).
 * {@code uq_dimension_company_slot} enforces one dimension per slot per company.
 * {@code is_mandatory=false} by default — opt-in governance (BR-CC-03, NFR-CC-01).
 */
@Getter
@Entity
@Table(name = "dimensions")
public class Dimension extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "slot", nullable = false, length = 20, updatable = false)
    private DimensionSlot slot;

    @Column(name = "code", nullable = false, length = 40)
    @Setter
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    @Setter
    private String name;

    /** P3: free-text dimension description; nullable. */
    @Column(name = "description", length = 255)
    @Setter
    private String description;

    /** True for the two seeded dimensions (COST_CENTRE, DEPARTMENT) — cannot be deleted. */
    @Column(name = "is_built_in", nullable = false, updatable = false)
    private boolean builtIn;

    /**
     * When true, every GL posting for this company must carry a value for this slot.
     * Off by default — a v1 deployment never breaks existing flows until a controller turns it on.
     */
    @Column(name = "is_mandatory", nullable = false)
    @Setter
    private boolean mandatory;

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

    protected Dimension() {
        // JPA
    }

    /** Constructor for the DimensionSeeder — new-company seed path. */
    public Dimension(Long companyId, DimensionSlot slot, String code, String name,
                     boolean builtIn, Long createdBy) {
        this.companyId  = companyId;
        this.slot       = slot;
        this.code       = code;
        this.name       = name;
        this.builtIn    = builtIn;
        this.mandatory  = false;
        this.createdBy  = createdBy;
    }
}
