package com.erp.modules.fixedassets.domain.entity;

import com.erp.modules.fixedassets.domain.enums.DepreciationMethod;
import com.erp.modules.fixedassets.domain.enums.FixedAssetStatus;
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
 * Fixed asset register entry (ADR-0030 D-2).
 * NBV is derived: {@code carrying_cost − accumulated_depreciation} — NOT stored.
 * Financial fields are immutable once IN_SERVICE; carrying_cost changes via revaluation only.
 */
@Getter
@Entity
@Table(name = "fixed_assets")
public class FixedAsset extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false)
    @Setter
    private Long branchId;

    @Column(name = "asset_number", nullable = false, length = 30)
    private String assetNumber;

    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    @Column(name = "name", nullable = false, length = 200)
    @Setter
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private FixedAssetStatus status = FixedAssetStatus.DRAFT;

    @Column(name = "acquisition_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal acquisitionCost;

    @Column(name = "salvage_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal salvageValue = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "depreciation_method", nullable = false, length = 20)
    @Setter
    private DepreciationMethod depreciationMethod;

    @Column(name = "life_periods", nullable = false)
    @Setter
    private int lifePeriods;

    @Column(name = "reducing_rate", precision = 9, scale = 4)
    @Setter
    private BigDecimal reducingRate;

    @Column(name = "acquisition_date", nullable = false)
    private LocalDate acquisitionDate;

    @Column(name = "depreciation_start_date", nullable = false)
    @Setter
    private LocalDate depreciationStartDate;

    /** Current depreciable base — changes on revaluation; schedule regenerates on this. */
    @Column(name = "carrying_cost", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal carryingCost;

    /** Running Σ of posted depreciation charges. */
    @Column(name = "accumulated_depreciation", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal accumulatedDepreciation = BigDecimal.ZERO;

    /** Running revaluation-up reserve held against this asset (GL side: revaluation reserve). */
    @Column(name = "revaluation_reserve_balance", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal revaluationReserveBalance = BigDecimal.ZERO;

    /** Scalar supplier id — no FK (cross-module). */
    @Column(name = "supplier_id")
    @Setter
    private Long supplierId;

    /** The AP supplier bill uid this was capitalised from; NULL for manual acquisitions. */
    @Column(name = "source_bill_uid", length = 26)
    @Setter
    private String sourceBillUid;

    @Column(name = "location", length = 200)
    @Setter
    private String location;

    /** Reserved for cost-centre dimension framework; nullable scalar, no FK (OQ-FA-07). */
    @Column(name = "cost_centre_id")
    @Setter
    private Long costCentreId;

    @Column(name = "asset_tag", length = 100)
    @Setter
    private String assetTag;

    /** The capitalisation journal uid — audit trace, set on IN_SERVICE. */
    @Column(name = "capitalised_gl_entry_uid", length = 26)
    @Setter
    private String capitalisedGlEntryUid;

    @Column(name = "disposed_at")
    @Setter
    private LocalDate disposedAt;

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

    protected FixedAsset() {
        // JPA
    }

    public FixedAsset(Long companyId, Long branchId, String assetNumber, Long categoryId,
                       String name, BigDecimal acquisitionCost, BigDecimal salvageValue,
                       DepreciationMethod depreciationMethod, int lifePeriods, BigDecimal reducingRate,
                       LocalDate acquisitionDate, LocalDate depreciationStartDate, Long createdBy) {
        this.companyId            = companyId;
        this.branchId             = branchId;
        this.assetNumber          = assetNumber;
        this.categoryId           = categoryId;
        this.name                 = name;
        this.acquisitionCost      = acquisitionCost;
        this.salvageValue         = salvageValue != null ? salvageValue : BigDecimal.ZERO;
        this.depreciationMethod   = depreciationMethod;
        this.lifePeriods          = lifePeriods;
        this.reducingRate         = reducingRate;
        this.acquisitionDate      = acquisitionDate;
        this.depreciationStartDate = depreciationStartDate;
        this.carryingCost         = acquisitionCost;
        this.createdBy            = createdBy;
    }

    /** Derived NBV — NOT stored; computed on demand. */
    public BigDecimal computeNbv() {
        return carryingCost.subtract(accumulatedDepreciation);
    }
}
