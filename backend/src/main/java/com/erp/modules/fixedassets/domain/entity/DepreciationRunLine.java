package com.erp.modules.fixedassets.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-asset charge in a depreciation run (ADR-0030 D-4).
 * One row per asset per run; charge_amount = the scheduled charge for the period.
 */
@Getter
@Entity
@Table(name = "depreciation_run_lines")
public class DepreciationRunLine extends UidEntity {

    @Column(name = "depreciation_run_id", nullable = false, updatable = false)
    private Long depreciationRunId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "fixed_asset_id", nullable = false, updatable = false)
    private Long fixedAssetId;

    @Column(name = "schedule_line_id", nullable = false, updatable = false)
    private Long scheduleLineId;

    @Column(name = "charge_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal chargeAmount;

    @Column(name = "accum_dep_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal accumDepAfter;

    @Column(name = "nbv_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal nbvAfter;

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

    protected DepreciationRunLine() {
        // JPA
    }

    public DepreciationRunLine(Long depreciationRunId, Long companyId, Long fixedAssetId,
                                Long scheduleLineId, BigDecimal chargeAmount,
                                BigDecimal accumDepAfter, BigDecimal nbvAfter, Long createdBy) {
        this.depreciationRunId = depreciationRunId;
        this.companyId         = companyId;
        this.fixedAssetId      = fixedAssetId;
        this.scheduleLineId    = scheduleLineId;
        this.chargeAmount      = chargeAmount;
        this.accumDepAfter     = accumDepAfter;
        this.nbvAfter          = nbvAfter;
        this.createdBy         = createdBy;
    }
}
