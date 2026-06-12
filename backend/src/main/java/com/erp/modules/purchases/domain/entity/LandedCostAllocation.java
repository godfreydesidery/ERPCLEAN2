package com.erp.modules.purchases.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/**
 * Per-receipt-line allocation amount for a landed cost (ADR-0027 D-5).
 * The maths audit — records each GR line's share of the total charge.
 * Immutable once written (CONFIRMED landed cost; BR-PROC-12).
 */
@Getter
@Entity
@Table(name = "landed_cost_allocations")
public class LandedCostAllocation extends UidEntity {

    @Column(name = "landed_cost_id", nullable = false, updatable = false)
    private Long landedCostId;

    @Column(name = "goods_receipt_line_id", nullable = false, updatable = false)
    private Long goodsReceiptLineId;

    @Column(name = "goods_receipt_line_uid", nullable = false, length = 26, updatable = false)
    private String goodsReceiptLineUid;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected LandedCostAllocation() {
        // JPA
    }

    public LandedCostAllocation(Long landedCostId, Long goodsReceiptLineId,
                                 String goodsReceiptLineUid, Long productId,
                                 Long companyId, Long branchId,
                                 BigDecimal allocatedAmount, String currency, Long createdBy) {
        this.landedCostId         = landedCostId;
        this.goodsReceiptLineId   = goodsReceiptLineId;
        this.goodsReceiptLineUid  = goodsReceiptLineUid;
        this.productId            = productId;
        this.companyId            = companyId;
        this.branchId             = branchId;
        this.allocatedAmount      = allocatedAmount;
        this.currency             = currency;
        this.createdBy            = createdBy;
    }
}
