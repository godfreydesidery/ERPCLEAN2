package com.erp.modules.crm.domain.entity;

import com.erp.modules.crm.domain.enums.PipelineStageType;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Configurable per-company pipeline stage (ADR-0031 D-4).
 * Seeded on company create by CrmStageSeeder; editable via PipelineStageService.
 * company_id only (no branch_id) — a stage set is a company-level config.
 */
@Getter
@Entity
@Table(name = "pipeline_stages")
public class PipelineStage extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "name", nullable = false, length = 60)
    @Setter
    private String name;

    @Column(name = "display_order", nullable = false)
    @Setter
    private short displayOrder;

    @Column(name = "default_probability", nullable = false, precision = 5, scale = 2)
    @Setter
    private BigDecimal defaultProbability;

    @Column(name = "is_active", nullable = false)
    @Setter
    private boolean active = true;

    /** Terminal-won funnel position (P3). */
    @Column(name = "is_won_stage", nullable = false)
    @Setter
    private boolean wonStage = false;

    /** Terminal-lost funnel position (P3). */
    @Column(name = "is_lost_stage", nullable = false)
    @Setter
    private boolean lostStage = false;

    /** Reconciles the stage with the orthogonal {@code Opportunity.opportunity_status} axis (P3, data-only). */
    @Enumerated(EnumType.STRING)
    @Column(name = "stage_type", length = 20)
    @Setter
    private PipelineStageType stageType;

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

    protected PipelineStage() { /* JPA */ }

    public PipelineStage(Long companyId, String name, short displayOrder,
                         BigDecimal defaultProbability, Long createdBy) {
        this.companyId = companyId;
        this.name = name;
        this.displayOrder = displayOrder;
        this.defaultProbability = defaultProbability;
        this.createdBy = createdBy;
    }
}
