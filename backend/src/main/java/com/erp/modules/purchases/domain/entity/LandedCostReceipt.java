package com.erp.modules.purchases.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * Junction: the GRs that a landed cost covers (ADR-0027 D-5).
 * Singular table name: {@code landed_cost_receipt}.
 */
@Getter
@Entity
@Table(name = "landed_cost_receipt")
public class LandedCostReceipt extends UidEntity {

    @Column(name = "landed_cost_id", nullable = false, updatable = false)
    private Long landedCostId;

    @Column(name = "goods_receipt_id", nullable = false, updatable = false)
    private Long goodsReceiptId;

    @Column(name = "goods_receipt_uid", nullable = false, length = 26, updatable = false)
    private String goodsReceiptUid;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected LandedCostReceipt() {
        // JPA
    }

    public LandedCostReceipt(Long landedCostId, Long goodsReceiptId, String goodsReceiptUid,
                              Long companyId, Long branchId, Long createdBy) {
        this.landedCostId     = landedCostId;
        this.goodsReceiptId   = goodsReceiptId;
        this.goodsReceiptUid  = goodsReceiptUid;
        this.companyId        = companyId;
        this.branchId         = branchId;
        this.createdBy        = createdBy;
    }
}
