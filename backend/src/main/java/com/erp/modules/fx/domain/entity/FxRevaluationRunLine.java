package com.erp.modules.fx.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-currency revaluation detail line for a run (ADR-0036 D-6).
 *
 * <p>One row per (currency, source_type, control_account) per run.
 * {@code adjustment_amount = revalued_base_amount - carrying_base_amount}
 * (signed: positive = gain, negative = loss).
 */
@Getter
@Entity
@Table(name = "fx_revaluation_run_lines")
public class FxRevaluationRunLine extends UidEntity {

    @Column(name = "fx_revaluation_run_id", nullable = false, updatable = false)
    private Long fxRevaluationRunId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** AR, AP, or CASH. */
    @Column(name = "source_type", nullable = false, length = 10, updatable = false)
    private String sourceType;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "control_account_id", nullable = false, updatable = false)
    private Long controlAccountId;

    /** Sum of outstanding_amount across open items (face / foreign currency). */
    @Column(name = "outstanding_txn_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal outstandingTxnAmount;

    /** Sum of base_outstanding_amount across open items — the frozen carrying value in base. */
    @Column(name = "carrying_base_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal carryingBaseAmount;

    /** Period-end spot rate (units of base per 1 unit of foreign). */
    @Column(name = "spot_rate", nullable = false, precision = 19, scale = 8)
    private BigDecimal spotRate;

    /** outstanding_txn_amount × spot_rate (HALF_UP, base minor units). */
    @Column(name = "revalued_base_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal revaluedBaseAmount;

    /** revalued_base_amount − carrying_base_amount (signed). */
    @Column(name = "adjustment_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal adjustmentAmount;

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

    protected FxRevaluationRunLine() {
        // JPA
    }

    public FxRevaluationRunLine(Long fxRevaluationRunId, Long companyId,
                                 String sourceType, String currency, Long controlAccountId,
                                 BigDecimal outstandingTxnAmount, BigDecimal carryingBaseAmount,
                                 BigDecimal spotRate, BigDecimal revaluedBaseAmount,
                                 BigDecimal adjustmentAmount, Long createdBy) {
        this.fxRevaluationRunId  = fxRevaluationRunId;
        this.companyId           = companyId;
        this.sourceType          = sourceType;
        this.currency            = currency;
        this.controlAccountId    = controlAccountId;
        this.outstandingTxnAmount = outstandingTxnAmount;
        this.carryingBaseAmount  = carryingBaseAmount;
        this.spotRate            = spotRate;
        this.revaluedBaseAmount  = revaluedBaseAmount;
        this.adjustmentAmount    = adjustmentAmount;
        this.createdBy           = createdBy;
    }
}
