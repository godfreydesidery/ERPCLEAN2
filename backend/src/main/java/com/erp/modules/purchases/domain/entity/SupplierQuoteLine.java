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
 * Supplier Quote line — per-line pricing (ADR-0027 D-4).
 * Stores the quoted unit cost per (supplier, product) for the last-quoted-price reference (BR-PROC-09).
 */
@Getter
@Entity
@Table(name = "supplier_quote_lines")
public class SupplierQuoteLine extends UidEntity {

    @Column(name = "supplier_quote_id", nullable = false, updatable = false)
    private Long supplierQuoteId;

    @Column(name = "rfq_line_id")
    private Long rfqLineId;

    @Column(name = "rfq_line_uid", length = 26)
    private String rfqLineUid;

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

    @Column(name = "quoted_qty", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal quotedQty;

    @Column(name = "quoted_qty_in_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal quotedQtyInBase;

    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal unitPriceAmount;

    @Column(name = "line_total_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal lineTotalAmount;

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

    protected SupplierQuoteLine() {
        // JPA
    }

    public SupplierQuoteLine(Long supplierQuoteId, Long rfqLineId, String rfqLineUid,
                              Long companyId, Long branchId, short lineNo,
                              Long productId, String productCode, String productName,
                              Long unitId, String unitName,
                              BigDecimal quotedQty, BigDecimal quotedQtyInBase,
                              BigDecimal unitPriceAmount, String currency, Long createdBy) {
        this.supplierQuoteId  = supplierQuoteId;
        this.rfqLineId        = rfqLineId;
        this.rfqLineUid       = rfqLineUid;
        this.companyId        = companyId;
        this.branchId         = branchId;
        this.lineNo           = lineNo;
        this.productId        = productId;
        this.productCode      = productCode;
        this.productName      = productName;
        this.unitId           = unitId;
        this.unitName         = unitName;
        this.quotedQty        = quotedQty;
        this.quotedQtyInBase  = quotedQtyInBase;
        this.unitPriceAmount  = unitPriceAmount;
        this.lineTotalAmount  = unitPriceAmount.multiply(quotedQty);
        this.currency         = currency;
        this.createdBy        = createdBy;
    }
}
