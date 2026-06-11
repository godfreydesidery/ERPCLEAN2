package com.erp.modules.fixedassets.domain.entity;

import com.erp.modules.fixedassets.domain.enums.DepreciationMethod;
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
 * Asset category — master per company; owns the three GL accounts (ADR-0030 D-2).
 * Per OQ-FA-04, an asset inherits the category accounts and cannot override them in v1.
 */
@Getter
@Entity
@Table(name = "asset_categories")
public class AssetCategory extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "code", nullable = false, length = 30)
    @Setter
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    @Setter
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_method", nullable = false, length = 20)
    @Setter
    private DepreciationMethod defaultMethod;

    @Column(name = "default_life_periods", nullable = false)
    @Setter
    private int defaultLifePeriods;

    @Column(name = "default_reducing_rate", precision = 9, scale = 4)
    @Setter
    private BigDecimal defaultReducingRate;

    /** FK → chart_of_accounts(id): the Fixed Asset capitalisation account. */
    @Column(name = "asset_account_id", nullable = false)
    @Setter
    private Long assetAccountId;

    /** FK → chart_of_accounts(id): the Accumulated Depreciation contra-asset account. */
    @Column(name = "accum_dep_account_id", nullable = false)
    @Setter
    private Long accumDepAccountId;

    /** FK → chart_of_accounts(id): the Depreciation Expense account. */
    @Column(name = "dep_expense_account_id", nullable = false)
    @Setter
    private Long depExpenseAccountId;

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

    protected AssetCategory() {
        // JPA
    }

    public AssetCategory(Long companyId, String code, String name,
                          DepreciationMethod defaultMethod, int defaultLifePeriods,
                          BigDecimal defaultReducingRate,
                          Long assetAccountId, Long accumDepAccountId, Long depExpenseAccountId,
                          Long createdBy) {
        this.companyId           = companyId;
        this.code                = code;
        this.name                = name;
        this.defaultMethod       = defaultMethod;
        this.defaultLifePeriods  = defaultLifePeriods;
        this.defaultReducingRate = defaultReducingRate;
        this.assetAccountId      = assetAccountId;
        this.accumDepAccountId   = accumDepAccountId;
        this.depExpenseAccountId = depExpenseAccountId;
        this.createdBy           = createdBy;
    }
}
