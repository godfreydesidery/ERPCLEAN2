package com.erp.modules.purchases.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Purchase return line — each line corresponds to a goods-receipt line being returned (ADR-0027 D-7).
 * {@code unit_cost_amount} is the ORIGINAL receipt cost from the GR line (immutable; used for reversal).
 */
@Getter
@Entity
@Table(name = "purchase_return_lines")
public class PurchaseReturnLine extends UidEntity {

    @Column(name = "purchase_return_id", nullable = false, updatable = false)
    private Long purchaseReturnId;

    @Column(name = "goods_receipt_line_id", nullable = false, updatable = false)
    private Long goodsReceiptLineId;

    @Column(name = "goods_receipt_line_uid", nullable = false, length = 26, updatable = false)
    private String goodsReceiptLineUid;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "line_no", nullable = false)
    private short lineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "product_code", nullable = false, length = 50)
    private String productCode;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    @Column(name = "unit_name", nullable = false, length = 50)
    private String unitName;

    /** Qty returned in the transaction UoM. */
    @Column(name = "returned_qty", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal returnedQty;

    /** Qty returned expressed in base UoM (for stock ledger). */
    @Column(name = "returned_qty_in_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal returnedQtyInBase;

    /** Original per-unit cost from the GR (for inventory reversal). */
    @Column(name = "unit_cost_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCostAmount;

    @Column(name = "line_value_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal lineValueAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

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

    protected PurchaseReturnLine() {
        // JPA
    }

    public PurchaseReturnLine(Long purchaseReturnId,
                              Long goodsReceiptLineId, String goodsReceiptLineUid,
                              Long companyId, Long branchId, short lineNo,
                              Long productId, String productCode, String productName,
                              Long unitId, String unitName,
                              BigDecimal returnedQty, BigDecimal returnedQtyInBase,
                              BigDecimal unitCostAmount, BigDecimal lineValueAmount,
                              String currency, Long createdBy) {
        this.purchaseReturnId      = purchaseReturnId;
        this.goodsReceiptLineId    = goodsReceiptLineId;
        this.goodsReceiptLineUid   = goodsReceiptLineUid;
        this.companyId             = companyId;
        this.branchId              = branchId;
        this.lineNo                = lineNo;
        this.productId             = productId;
        this.productCode           = productCode;
        this.productName           = productName;
        this.unitId                = unitId;
        this.unitName              = unitName;
        this.returnedQty           = returnedQty;
        this.returnedQtyInBase     = returnedQtyInBase;
        this.unitCostAmount        = unitCostAmount;
        this.lineValueAmount       = lineValueAmount;
        this.currency              = currency;
        this.createdBy             = createdBy;
    }
}
