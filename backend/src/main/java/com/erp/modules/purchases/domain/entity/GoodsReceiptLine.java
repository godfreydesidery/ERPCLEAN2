package com.erp.modules.purchases.domain.entity;

import com.erp.platform.common.domain.Ulid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/**
 * One received product line on a Goods Receipt (ADR-0011 D-2, FR-PURCH-07).
 *
 * <p>Does NOT extend {@link com.erp.platform.common.domain.UidEntity} — GR lines are
 * entered at draft and frozen at receive (BR-PURCH-05); no updated_* columns.
 *
 * <p>FKs to {@code goods_receipts} (owning header) AND {@code purchase_order_lines} (the PO line
 * this draw-down refers to — the per-line outstanding match, ADR-0011 D-2).
 *
 * <p>qty_in_base is SNAPSHOTTED at GR entry — the STOCK.RECEIVED payload carries this directly
 * with no live unit conversion (ADR-0011 D-8). unit_cost_amount is INHERITED from the PO line
 * (FR-PURCH-05).
 */
@Getter
@Entity
@Table(name = "goods_receipt_lines")
public class GoodsReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, updatable = false, length = 26)
    private String uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goods_receipt_id", nullable = false, updatable = false)
    private GoodsReceipt goodsReceipt;

    /** FK → purchase_order_lines.id; the PO line this draws down (FR-PURCH-07). */
    @Column(name = "purchase_order_line_id", nullable = false, updatable = false)
    private Long purchaseOrderLineId;

    /** Denormalised from parent GR; set at construction; immutable. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** Denormalised from parent GR; set at construction; immutable. */
    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** 1-based ordinal; unique per GR. */
    @Column(name = "line_no", nullable = false)
    private short lineNo;

    /** FK → products.id; scalar (= PO line product). */
    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    /** Snapshot (carried from PO line at GR entry). */
    @Column(name = "product_code", nullable = false, length = 20)
    private String productCode;

    /** Snapshot. */
    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    /** FK → units_of_measure.id; scalar (PO line's unit). */
    @Column(name = "unit_id", nullable = false, updatable = false)
    private Long unitId;

    /** Snapshot. */
    @Column(name = "unit_name", nullable = false, length = 60)
    private String unitName;

    /** Received quantity in unit_id units; CHECK > 0. */
    @Column(name = "received_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal receivedQty;

    /**
     * Snapshotted received_qty in product base unit — the value added to the PO line's
     * received_qty_in_base (ADR-0011 D-3) and the qtyInBase in the STOCK.RECEIVED payload (D-8).
     * CHECK > 0.
     */
    @Column(name = "qty_in_base", nullable = false, precision = 19, scale = 6)
    private BigDecimal qtyInBase;

    /** Inherited from PO line (FR-PURCH-05); CHECK >= 0. */
    @Column(name = "unit_cost_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCostAmount;

    /** Computed: unit_cost_amount × received_qty (received cost for the record; ADR-0011 D-5). */
    @Column(name = "line_cost_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineCostAmount;

    /** Document currency; denormalised from PO/GR header. */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    void assignUid() {
        if (uid == null) {
            uid = Ulid.next();
        }
    }

    protected GoodsReceiptLine() {
        // JPA
    }

    public GoodsReceiptLine(GoodsReceipt goodsReceipt, Long purchaseOrderLineId, short lineNo,
                            Long productId, String productCode, String productName,
                            Long unitId, String unitName,
                            BigDecimal receivedQty, BigDecimal qtyInBase,
                            BigDecimal unitCostAmount, Long createdBy) {
        this.goodsReceipt        = goodsReceipt;
        this.purchaseOrderLineId = purchaseOrderLineId;
        this.companyId           = goodsReceipt.getCompanyId();   // denormalise
        this.branchId            = goodsReceipt.getBranchId();    // denormalise
        this.lineNo              = lineNo;
        this.productId           = productId;
        this.productCode         = productCode;
        this.productName         = productName;
        this.unitId              = unitId;
        this.unitName            = unitName;
        this.receivedQty         = receivedQty;
        this.qtyInBase           = qtyInBase;
        this.unitCostAmount      = unitCostAmount;
        this.lineCostAmount      = unitCostAmount.multiply(receivedQty);
        this.currency            = goodsReceipt.getCompanyId() != null
                                    ? null : null; // set in service from PO currency
        this.createdBy           = createdBy;
    }

    /** Full constructor with explicit currency (preferred — called by service). */
    public GoodsReceiptLine(GoodsReceipt goodsReceipt, Long purchaseOrderLineId, short lineNo,
                            Long productId, String productCode, String productName,
                            Long unitId, String unitName,
                            BigDecimal receivedQty, BigDecimal qtyInBase,
                            BigDecimal unitCostAmount, String currency, Long createdBy) {
        this.goodsReceipt        = goodsReceipt;
        this.purchaseOrderLineId = purchaseOrderLineId;
        this.companyId           = goodsReceipt.getCompanyId();
        this.branchId            = goodsReceipt.getBranchId();
        this.lineNo              = lineNo;
        this.productId           = productId;
        this.productCode         = productCode;
        this.productName         = productName;
        this.unitId              = unitId;
        this.unitName            = unitName;
        this.receivedQty         = receivedQty;
        this.qtyInBase           = qtyInBase;
        this.unitCostAmount      = unitCostAmount;
        this.lineCostAmount      = unitCostAmount.multiply(receivedQty);
        this.currency            = currency;
        this.createdBy           = createdBy;
    }
}
