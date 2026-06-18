package com.erp.modules.purchases.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * Purchase Requisition line (ADR-0027 D-3).
 * Extends UidEntity for uid + @Version optimistic lock.
 */
@Getter
@Entity
@Table(name = "purchase_requisition_lines")
public class PurchaseRequisitionLine extends UidEntity {

    @Column(name = "purchase_requisition_id", nullable = false, updatable = false)
    private Long purchaseRequisitionId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "line_no", nullable = false)
    private short lineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "product_code", nullable = false, length = 60)
    private String productCode;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private Long unitId;

    @Column(name = "unit_name", nullable = false, length = 60)
    private String unitName;

    @Column(name = "requested_qty", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal requestedQty;

    @Column(name = "requested_qty_in_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal requestedQtyInBase;

    @Column(name = "estimated_unit_cost", precision = 19, scale = 4)
    @Setter
    private BigDecimal estimatedUnitCost;

    /** P2: optional per-line required-by date. */
    @Column(name = "required_by_date")
    @Setter
    private LocalDate requiredByDate;

    /** P2: soft-FK suppliers.id — line-level suggested source. Nullable. */
    @Column(name = "suggested_supplier_id")
    @Setter
    private Long suggestedSupplierId;

    /** P2: per-line traceability scalar uid to the produced PO line. Nullable. */
    @Column(name = "converted_to_po_line_uid", length = 26)
    @Setter
    private String convertedToPoLineUid;

    @Column(name = "note", length = 255)
    @Setter
    private String note;

    @Column(name = "currency", length = 3)
    @Setter
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

    protected PurchaseRequisitionLine() {
        // JPA
    }

    public PurchaseRequisitionLine(Long purchaseRequisitionId, Long companyId, Long branchId,
                                    short lineNo, Long productId, String productCode,
                                    String productName, Long unitId, String unitName,
                                    BigDecimal requestedQty, BigDecimal requestedQtyInBase,
                                    BigDecimal estimatedUnitCost, String note, String currency,
                                    Long createdBy) {
        this.purchaseRequisitionId = purchaseRequisitionId;
        this.companyId             = companyId;
        this.branchId              = branchId;
        this.lineNo                = lineNo;
        this.productId             = productId;
        this.productCode           = productCode;
        this.productName           = productName;
        this.unitId                = unitId;
        this.unitName              = unitName;
        this.requestedQty          = requestedQty;
        this.requestedQtyInBase    = requestedQtyInBase;
        this.estimatedUnitCost     = estimatedUnitCost;
        this.note                  = note;
        this.currency              = CurrencyCode.ofNullable(currency);
        this.createdBy             = createdBy;
    }
}
