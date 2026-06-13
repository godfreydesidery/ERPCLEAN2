package com.erp.modules.fixedassets.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * One period line in the generated depreciation schedule for a fixed asset (ADR-0030 D-5).
 * Created by {@code DepreciationScheduleService.generate} on place-in-service,
 * regenerated on revaluation (schedule_version bumped, prior unposted lines superseded).
 */
@Getter
@Entity
@Table(name = "depreciation_schedule_lines")
public class DepreciationScheduleLine extends UidEntity {

    @Column(name = "fixed_asset_id", nullable = false, updatable = false)
    private Long fixedAssetId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "period_seq", nullable = false)
    private int periodSeq;

    @Column(name = "schedule_version", nullable = false)
    private int scheduleVersion = 1;

    @Column(name = "period_date", nullable = false)
    private LocalDate periodDate;

    @Column(name = "planned_charge", nullable = false, precision = 19, scale = 4)
    private BigDecimal plannedCharge;

    @Column(name = "accumulated_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal accumulatedAfter;

    @Column(name = "nbv_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal nbvAfter;

    @Column(name = "posted", nullable = false)
    @Setter
    private boolean posted = false;

    /** Set when posted — FK → depreciation_runs(id). */
    @Column(name = "depreciation_run_id")
    @Setter
    private Long depreciationRunId;

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

    protected DepreciationScheduleLine() {
        // JPA
    }

    public DepreciationScheduleLine(Long fixedAssetId, Long companyId, int periodSeq,
                                     int scheduleVersion, LocalDate periodDate,
                                     BigDecimal plannedCharge, BigDecimal accumulatedAfter,
                                     BigDecimal nbvAfter, Long createdBy) {
        this.fixedAssetId    = fixedAssetId;
        this.companyId       = companyId;
        this.periodSeq       = periodSeq;
        this.scheduleVersion = scheduleVersion;
        this.periodDate      = periodDate;
        this.plannedCharge   = plannedCharge;
        this.accumulatedAfter = accumulatedAfter;
        this.nbvAfter        = nbvAfter;
        this.createdBy       = createdBy;
    }
}
