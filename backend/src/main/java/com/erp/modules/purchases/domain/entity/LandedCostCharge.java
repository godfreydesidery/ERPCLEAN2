package com.erp.modules.purchases.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.modules.purchases.domain.enums.LandedCostChargeType;
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
 * Landed cost charge line — freight/duty/etc. (ADR-0027 D-5).
 */
@Getter
@Entity
@Table(name = "landed_cost_charges")
public class LandedCostCharge extends UidEntity {

    @Column(name = "landed_cost_id", nullable = false, updatable = false)
    private Long landedCostId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "line_no", nullable = false)
    private short lineNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_type", nullable = false, length = 20)
    private LandedCostChargeType chargeType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal amount;

    @Column(name = "is_billed", nullable = false)
    @Setter
    private boolean billed = false;

    /** Scalar uid of the freight bill if is_billed = true. No FK (scalar cross-doc ref). */
    @Column(name = "supplier_bill_uid", length = 26)
    @Setter
    private String supplierBillUid;

    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

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

    protected LandedCostCharge() {
        // JPA
    }

    public LandedCostCharge(Long landedCostId, Long companyId, Long branchId, short lineNo,
                             LandedCostChargeType chargeType, BigDecimal amount,
                             boolean billed, String supplierBillUid,
                             String currency, Long createdBy) {
        this.landedCostId    = landedCostId;
        this.companyId       = companyId;
        this.branchId        = branchId;
        this.lineNo          = lineNo;
        this.chargeType      = chargeType;
        this.amount          = amount;
        this.billed          = billed;
        this.supplierBillUid = supplierBillUid;
        this.currency        = CurrencyCode.ofNullable(currency);
        this.createdBy       = createdBy;
    }
}
