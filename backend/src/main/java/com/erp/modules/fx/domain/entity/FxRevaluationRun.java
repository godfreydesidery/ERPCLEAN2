package com.erp.modules.fx.domain.entity;

import com.erp.modules.fx.domain.enums.FxRevaluationRunStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * FX revaluation run header — the idempotency anchor (ADR-0036 D-6).
 *
 * <p>{@code uq_fx_revaluation_run_company_period} on (company_id, fiscal_period_id) ensures
 * at most one run per (company, fiscal period), cloned from {@code DepreciationRun} (ADR-0030 D-4).
 */
@Getter
@Entity
@Table(name = "fx_revaluation_runs")
public class FxRevaluationRun extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    @Setter
    private Long branchId;

    @Column(name = "run_number", nullable = false, length = 30)
    private String runNumber;

    @Column(name = "fiscal_period_id", nullable = false, updatable = false)
    private Long fiscalPeriodId;

    @Column(name = "posting_date", nullable = false)
    private LocalDate postingDate;

    @Column(name = "spot_rate_date", nullable = false)
    private LocalDate spotRateDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private FxRevaluationRunStatus status = FxRevaluationRunStatus.PREVIEWED;

    @Column(name = "total_gain_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal totalGainAmount = BigDecimal.ZERO;

    @Column(name = "total_loss_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal totalLossAmount = BigDecimal.ZERO;

    @Column(name = "net_adjustment_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal netAdjustmentAmount = BigDecimal.ZERO;

    /** UID of the GL journal entry posted for this run (null until status == POSTED). */
    @Column(name = "gl_entry_uid", length = 26)
    @Setter
    private String glEntryUid;

    /** UID of the reversing GL entry (null until the reversal has been posted). */
    @Column(name = "reversal_gl_entry_uid", length = 26)
    @Setter
    private String reversalGlEntryUid;

    @Column(name = "executed_at")
    @Setter
    private Instant executedAt;

    /** app_users.id of the actor who executed (posted) this run (P2-M1). */
    @Column(name = "executed_by")
    @Setter
    private Long executedBy;

    /** Effective date of the auto-reversal journal — first day of next period (P2-M1). */
    @Column(name = "reversal_date")
    @Setter
    private java.time.LocalDate reversalDate;

    /** fiscal_periods.id into which the auto-reversal was posted (P2-M1). */
    @Column(name = "reversal_period_id")
    @Setter
    private Long reversalPeriodId;

    /** Rate type used for this run (mirrors currency_rates.rate_type); e.g. SPOT (P2-M1). */
    @Column(name = "rate_type", length = 20)
    @Setter
    private String rateType;

    /** Human-readable summary of what was revalued (e.g. "AR:USD,EUR AP:USD") (P2-M1). */
    @Column(name = "scope_summary", length = 255)
    @Setter
    private String scopeSummary;

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

    protected FxRevaluationRun() {
        // JPA
    }

    public FxRevaluationRun(Long companyId, Long branchId, String runNumber,
                             Long fiscalPeriodId, LocalDate postingDate, LocalDate spotRateDate,
                             Long createdBy) {
        this.companyId      = companyId;
        this.branchId       = branchId;
        this.runNumber      = runNumber;
        this.fiscalPeriodId = fiscalPeriodId;
        this.postingDate    = postingDate;
        this.spotRateDate   = spotRateDate;
        this.createdBy      = createdBy;
    }
}
