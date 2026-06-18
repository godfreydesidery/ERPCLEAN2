package com.erp.modules.fixedassets.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.modules.fixedassets.domain.enums.DepreciationRunStatus;
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
 * Depreciation run header — the idempotency anchor (ADR-0030 D-4).
 * {@code uq_depreciation_run_company_period} on (company_id, fiscal_period_id) ensures
 * at most one run per (company, fiscal period).
 */
@Getter
@Entity
@Table(name = "depreciation_runs")
public class DepreciationRun extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "run_number", nullable = false, length = 30)
    private String runNumber;

    @Column(name = "fiscal_period_id", nullable = false, updatable = false)
    private Long fiscalPeriodId;

    @Column(name = "posting_date", nullable = false)
    private LocalDate postingDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DepreciationRunStatus status = DepreciationRunStatus.POSTED;

    @Column(name = "total_charge_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal totalChargeAmount;

    @Column(name = "asset_count", nullable = false)
    @Setter
    private int assetCount;

    @Column(name = "gl_entry_uid", nullable = false, length = 26)
    @Setter
    private String glEntryUid;

    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt = Instant.now();

    /** Actor who executed the run (NULLABLE; back-filled going forward). */
    @Column(name = "executed_by")
    @Setter
    private Long executedBy;

    /**
     * Reversal marker (P3, data-only). Set when this run is reversed.
     * The reversal POSTING lifecycle (contra GL entry, status transition) is DEFERRED.
     */
    @Column(name = "reversed_at")
    @Setter
    private Instant reversedAt;

    /** uid of the run this entry reverses (NULL for a normal run). Data-only; posting DEFERRED. */
    @Column(name = "reversal_of_run_uid", length = 26)
    @Setter
    private String reversalOfRunUid;

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

    protected DepreciationRun() {
        // JPA
    }

    public DepreciationRun(Long companyId, String runNumber, Long fiscalPeriodId,
                            LocalDate postingDate, String currency, Long createdBy) {
        this.companyId      = companyId;
        this.runNumber      = runNumber;
        this.fiscalPeriodId = fiscalPeriodId;
        this.postingDate    = postingDate;
        this.currency       = CurrencyCode.ofNullable(currency);
        this.createdBy      = createdBy;
    }
}
