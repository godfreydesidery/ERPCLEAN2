package com.erp.modules.stock.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A line item on a stock transfer (ADR-0028 D-5).
 * Immutable after creation (no update domain behaviour).
 * Extends {@link UidEntity} — carries {@code @Version} via the superclass.
 */
@Entity
@Table(name = "stock_transfer_lines")
public class StockTransferLine extends UidEntity {

    @Column(name = "stock_transfer_id", nullable = false, updatable = false)
    private Long stockTransferId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "line_no", nullable = false, updatable = false)
    private short lineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "product_code", nullable = false, updatable = false, length = 50)
    private String productCode;

    @Column(name = "product_name", nullable = false, updatable = false, length = 255)
    private String productName;

    @Column(name = "unit_id", updatable = false)
    private Long unitId;

    @Column(name = "unit_name", updatable = false, length = 50)
    private String unitName;

    @Column(name = "qty_transferred", nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal qtyTransferred;

    @Column(name = "qty_transferred_base", nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal qtyTransferredBase;

    /** Attributed value at dispatch (qty × source avg_cost). Diagnostic, not ledger (D-5). */
    @Column(name = "value_amount", updatable = false, precision = 19, scale = 4)
    private BigDecimal valueAmount;

    @Column(name = "currency", nullable = false, updatable = false, length = 10)
    private String currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected StockTransferLine() {
        // JPA
    }

    public StockTransferLine(Long stockTransferId, Long companyId, short lineNo,
                              Long productId, String productCode, String productName,
                              Long unitId, String unitName,
                              BigDecimal qtyTransferred, BigDecimal qtyTransferredBase,
                              BigDecimal valueAmount, String currency, Long createdBy) {
        this.stockTransferId   = stockTransferId;
        this.companyId         = companyId;
        this.lineNo            = lineNo;
        this.productId         = productId;
        this.productCode       = productCode;
        this.productName       = productName;
        this.unitId            = unitId;
        this.unitName          = unitName;
        this.qtyTransferred    = qtyTransferred;
        this.qtyTransferredBase = qtyTransferredBase;
        this.valueAmount       = valueAmount;
        this.currency          = currency;
        this.createdBy         = createdBy;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long       getStockTransferId()   { return stockTransferId; }
    public Long       getCompanyId()         { return companyId; }
    public short      getLineNo()            { return lineNo; }
    public Long       getProductId()         { return productId; }
    public String     getProductCode()       { return productCode; }
    public String     getProductName()       { return productName; }
    public Long       getUnitId()            { return unitId; }
    public String     getUnitName()          { return unitName; }
    public BigDecimal getQtyTransferred()    { return qtyTransferred; }
    public BigDecimal getQtyTransferredBase(){ return qtyTransferredBase; }
    public BigDecimal getValueAmount()       { return valueAmount; }
    public String     getCurrency()          { return currency; }
    public Instant    getCreatedAt()         { return createdAt; }
    public Long       getCreatedBy()         { return createdBy; }
    public Instant    getUpdatedAt()         { return updatedAt; }
    public Long       getUpdatedBy()         { return updatedBy; }
}
