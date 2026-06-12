package com.erp.modules.ap.domain.entity;

import com.erp.platform.common.domain.Ulid;
import jakarta.persistence.Column;
import lombok.Setter;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/**
 * One product line on a supplier bill (ADR-0015 D-2b).
 * Carries scalar po_line_uid / gr_line_uid for the 3-way match (no cross-module FK — D-11).
 */
@Getter
@Entity
@Table(name = "supplier_bill_lines")
public class SupplierBillLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, updatable = false, length = 26)
    private String uid;

    @Column(name = "supplier_bill_id", nullable = false, updatable = false)
    private Long supplierBillId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "line_no", nullable = false)
    private short lineNo;

    @Column(name = "product_id")
    private Long productId;

    /** Scalar uid of the PO line this bill line matches — no FK (ADR-0015 D-11). */
    @Column(name = "po_line_uid", length = 26)
    private String poLineUid;

    /** Scalar uid of the GR line this bill line draws against — no FK (ADR-0015 D-11). */
    @Column(name = "gr_line_uid", length = 26)
    private String grLineUid;

    /**
     * Scalar uid of the landed cost that generated this freight/duty charge line (ADR-0027 D-5, V34).
     * NULL for normal purchase lines.
     */
    @Column(name = "landed_cost_uid", length = 26)
    @Setter
    private String landedCostUid;

    @Column(name = "description", nullable = false, length = 200)
    private String description;

    @Column(name = "billed_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal billedQty;

    @Column(name = "unit_cost_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCostAmount;

    @Column(name = "line_net_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineNetAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    void assignUid() {
        if (uid == null) uid = Ulid.next();
    }

    protected SupplierBillLine() {
        // JPA
    }

    public SupplierBillLine(Long supplierBillId, Long companyId, Long branchId, short lineNo,
                            Long productId, String poLineUid, String grLineUid,
                            String description, BigDecimal billedQty,
                            BigDecimal unitCostAmount, String currency, Long createdBy) {
        this.supplierBillId  = supplierBillId;
        this.companyId       = companyId;
        this.branchId        = branchId;
        this.lineNo          = lineNo;
        this.productId       = productId;
        this.poLineUid       = poLineUid;
        this.grLineUid       = grLineUid;
        this.description     = description;
        this.billedQty       = billedQty;
        this.unitCostAmount  = unitCostAmount;
        this.lineNetAmount   = unitCostAmount.multiply(billedQty);
        this.currency        = currency;
        this.createdBy       = createdBy;
    }
}
