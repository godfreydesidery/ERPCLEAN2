package com.erp.modules.fixedassets.domain.entity;

import com.erp.modules.fixedassets.domain.enums.RevaluationDirection;
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
 * Asset revaluation record — multiple allowed per asset over its life (ADR-0030 D-6).
 */
@Getter
@Entity
@Table(name = "asset_revaluations")
public class AssetRevaluation extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "fixed_asset_id", nullable = false, updatable = false)
    private Long fixedAssetId;

    @Column(name = "revaluation_date", nullable = false)
    private LocalDate revaluationDate;

    @Column(name = "fiscal_period_id", nullable = false)
    private Long fiscalPeriodId;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private RevaluationDirection direction;

    /** Always positive — the magnitude of the upward or downward change. */
    @Column(name = "delta_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal deltaAmount;

    @Column(name = "carrying_before", nullable = false, precision = 19, scale = 4)
    private BigDecimal carryingBefore;

    @Column(name = "carrying_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal carryingAfter;

    /**
     * The GL journal uid posted for this revaluation. NOT NULL — the DB enforces that a
     * persisted revaluation must have a successful GL post (ADR-0030 D-6). The service sets this
     * after posting the GL journal in the same TX (em.persist is deferred until flush).
     */
    @Column(name = "gl_entry_uid", nullable = false, length = 26)
    @Setter
    private String glEntryUid;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "reason", length = 255)
    private String reason;

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

    protected AssetRevaluation() {
        // JPA
    }

    public AssetRevaluation(Long companyId, Long branchId, Long fixedAssetId,
                             LocalDate revaluationDate, Long fiscalPeriodId,
                             RevaluationDirection direction, BigDecimal deltaAmount,
                             BigDecimal carryingBefore, BigDecimal carryingAfter,
                             String currency, String reason, Long createdBy) {
        this.companyId      = companyId;
        this.branchId       = branchId;
        this.fixedAssetId   = fixedAssetId;
        this.revaluationDate = revaluationDate;
        this.fiscalPeriodId = fiscalPeriodId;
        this.direction      = direction;
        this.deltaAmount    = deltaAmount;
        this.carryingBefore = carryingBefore;
        this.carryingAfter  = carryingAfter;
        this.currency       = currency;
        this.reason         = reason;
        this.createdBy      = createdBy;
    }
}
