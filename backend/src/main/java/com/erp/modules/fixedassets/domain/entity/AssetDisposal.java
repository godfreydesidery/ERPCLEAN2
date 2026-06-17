package com.erp.modules.fixedassets.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.modules.fixedassets.domain.enums.AssetDisposalType;
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
 * Asset disposal record — one per asset (terminal; uq_asset_disposal_asset enforces) (ADR-0030 D-6).
 */
@Getter
@Entity
@Table(name = "asset_disposals")
public class AssetDisposal extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "fixed_asset_id", nullable = false, updatable = false)
    private Long fixedAssetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "disposal_type", nullable = false, length = 20)
    private AssetDisposalType disposalType;

    @Column(name = "disposal_date", nullable = false)
    private LocalDate disposalDate;

    @Column(name = "fiscal_period_id", nullable = false)
    private Long fiscalPeriodId;

    @Column(name = "proceeds_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal proceedsAmount;

    @Column(name = "nbv_at_disposal", nullable = false, precision = 19, scale = 4)
    private BigDecimal nbvAtDisposal;

    /** Signed: positive = gain, negative = loss. */
    @Column(name = "gain_loss_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal gainLossAmount;

    /**
     * The GL journal uid posted for this disposal. Nullable — the disposal row is persisted first
     * to obtain its uid for use as the GL sourceRef, then gl_entry_uid is set after the GL post
     * returns in the same TX (ADR-0030 D-6). The DB column is also nullable for the same reason.
     */
    @Column(name = "gl_entry_uid", nullable = true, length = 26)
    @Setter
    private String glEntryUid;

    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

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

    protected AssetDisposal() {
        // JPA
    }

    public AssetDisposal(Long companyId, Long branchId, Long fixedAssetId,
                          AssetDisposalType disposalType, LocalDate disposalDate,
                          Long fiscalPeriodId, BigDecimal proceedsAmount, BigDecimal nbvAtDisposal,
                          BigDecimal gainLossAmount, String currency, String reason,
                          Long createdBy) {
        this.companyId       = companyId;
        this.branchId        = branchId;
        this.fixedAssetId    = fixedAssetId;
        this.disposalType    = disposalType;
        this.disposalDate    = disposalDate;
        this.fiscalPeriodId  = fiscalPeriodId;
        this.proceedsAmount  = proceedsAmount;
        this.nbvAtDisposal   = nbvAtDisposal;
        this.gainLossAmount  = gainLossAmount;
        this.currency        = CurrencyCode.ofNullable(currency);
        this.reason          = reason;
        this.createdBy       = createdBy;
    }
}
