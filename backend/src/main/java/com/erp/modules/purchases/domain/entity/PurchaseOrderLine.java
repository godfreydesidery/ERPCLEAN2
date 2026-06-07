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
import lombok.Setter;

/**
 * One ordered product line on a Purchase Order (ADR-0011 D-2, FR-PURCH-04).
 *
 * <p>Does NOT extend {@link com.erp.platform.common.domain.UidEntity} — lines are
 * add/remove/replace only (no header-level @Version here; the PO header's @Version guards
 * concurrent modification). They DO carry a uid for external addressing (line uid scoped under
 * its PO uid in the API, ADR-0011 D-12).
 *
 * <p>company_id and branch_id are DENORMALISED from the parent header (set-once, immutable —
 * tenant predicate without join, ADR-0011 D-2).
 *
 * <p>received_qty_in_base is MAINTAINED by {@code OutstandingTracker} (ADR-0011 D-3): always
 * updated in the same TX as the GR receive/void that changes it. The DB CHECK
 * (received_qty_in_base <= ordered_qty_in_base) is the structural backstop; the service does the
 * friendly per-receipt validation before calling the tracker.
 *
 * <p>Snapshot columns (product_code, product_name, unit_name, unit_cost_amount, ordered_qty_in_base)
 * are set at line-add (draft) and frozen at order-placement (BR-PURCH-05).
 */
@Getter
@Entity
@Table(name = "purchase_order_lines")
public class PurchaseOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, updatable = false, length = 26)
    private String uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    /** Denormalised from parent PO; set at construction; immutable. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** Denormalised from parent PO; set at construction; immutable. */
    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** 1-based ordinal; unique per PO. */
    @Column(name = "line_no", nullable = false)
    private short lineNo;

    /** FK → products.id; scalar — no cross-module @ManyToOne (ADR-0011 D-1). */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** Snapshot of product code at order-placement. */
    @Column(name = "product_code", nullable = false, length = 20)
    @Setter
    private String productCode;

    /** Snapshot of product name at order-placement. */
    @Column(name = "product_name", nullable = false, length = 200)
    @Setter
    private String productName;

    /** FK → units_of_measure.id; scalar. */
    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    /** Snapshot of unit name at order-placement. */
    @Column(name = "unit_name", nullable = false, length = 60)
    @Setter
    private String unitName;

    /** Ordered quantity in unit_id units; CHECK > 0 (ADR-0011 D-2). */
    @Column(name = "ordered_qty", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal orderedQty;

    /** ordered_qty converted to product base unit — snapshotted at order-placement (ADR-0011 D-2). */
    @Column(name = "ordered_qty_in_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal orderedQtyInBase;

    /**
     * MAINTAINED cumulative received quantity in base units across non-void GR lines (ADR-0011 D-3).
     * Updated by {@code OutstandingTracker} in the same TX as the GR receive/void.
     * DEFAULT 0; CHECK >= 0 AND <= ordered_qty_in_base (DB backstop).
     */
    @Column(name = "received_qty_in_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal receivedQtyInBase = BigDecimal.ZERO;

    /** Unit cost per unit_id (a Money amount, ADR-0005). Required; >= 0. */
    @Column(name = "unit_cost_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal unitCostAmount;

    /** Computed: unit_cost_amount × ordered_qty; stored (ADR-0011 D-5). */
    @Column(name = "line_total_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal lineTotalAmount;

    /** Document currency; denormalised from parent PO (BR-PURCH-04). */
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

    @PrePersist
    void assignUid() {
        if (uid == null) {
            uid = Ulid.next();
        }
    }

    protected PurchaseOrderLine() {
        // JPA
    }

    public PurchaseOrderLine(PurchaseOrder purchaseOrder, short lineNo,
                             Long productId, String productCode, String productName,
                             Long unitId, String unitName,
                             BigDecimal orderedQty, BigDecimal orderedQtyInBase,
                             BigDecimal unitCostAmount, BigDecimal lineTotalAmount,
                             Long createdBy) {
        this.purchaseOrder    = purchaseOrder;
        this.companyId        = purchaseOrder.getCompanyId();   // denormalise
        this.branchId         = purchaseOrder.getBranchId();    // denormalise
        this.currency         = purchaseOrder.getCurrency();    // denormalise
        this.lineNo           = lineNo;
        this.productId        = productId;
        this.productCode      = productCode;
        this.productName      = productName;
        this.unitId           = unitId;
        this.unitName         = unitName;
        this.orderedQty       = orderedQty;
        this.orderedQtyInBase = orderedQtyInBase;
        this.unitCostAmount   = unitCostAmount;
        this.lineTotalAmount  = lineTotalAmount;
        this.createdBy        = createdBy;
    }
}
