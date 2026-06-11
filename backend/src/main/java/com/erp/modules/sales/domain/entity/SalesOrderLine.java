package com.erp.modules.sales.domain.entity;

import com.erp.modules.products.domain.enums.VatStatus;
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
 * Sales order line — carries the running open-quantity tracking that drives the rollup (ADR-0021 D-4).
 *
 * <p>qty_fulfilled_base, qty_invoiced_base, qty_reserved_base are the running counters.
 * DB CHECKs enforce: fulfilled <= ordered, invoiced <= fulfilled, reserved >= 0.
 */
@Getter
@Entity
@Table(name = "sales_order_lines")
public class SalesOrderLine extends UidEntity {

    @Column(name = "sales_order_id", nullable = false, updatable = false)
    private Long salesOrderId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "line_no", nullable = false)
    @Setter
    private short lineNo;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_code", nullable = false, length = 60)
    private String productCode;

    @Column(name = "product_name", nullable = false, length = 120)
    private String productName;

    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    @Column(name = "unit_name", nullable = false, length = 60)
    private String unitName;

    @Column(name = "qty_ordered", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal qtyOrdered;

    @Column(name = "qty_ordered_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal qtyOrderedBase;

    /** Running Σ delivered in base units; incremented by DeliveryService. */
    @Column(name = "qty_fulfilled_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal qtyFulfilledBase = BigDecimal.ZERO;

    /** Running Σ invoiced in base units; incremented by invoicing. */
    @Column(name = "qty_invoiced_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal qtyInvoicedBase = BigDecimal.ZERO;

    /** Current open reservation in base units; set on confirm, decremented on delivery/cancel. */
    @Column(name = "qty_reserved_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal qtyReservedBase = BigDecimal.ZERO;

    @Column(name = "list_price_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal listPriceAmount;

    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal unitPriceAmount;

    @Column(name = "price_overridden", nullable = false)
    @Setter
    private boolean priceOverridden = false;

    @Column(name = "overridden_by")
    @Setter
    private Long overriddenBy;

    @Column(name = "line_discount_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal lineDiscountAmount;

    @Column(name = "line_discount_percent", precision = 9, scale = 4)
    @Setter
    private BigDecimal lineDiscountPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "vat_status", nullable = false, length = 20)
    private VatStatus vatStatus;

    @Column(name = "vat_rate", nullable = false, precision = 9, scale = 4)
    @Setter
    private BigDecimal vatRate;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal grossAmount = BigDecimal.ZERO;

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

    protected SalesOrderLine() {
        // JPA
    }

    public SalesOrderLine(Long salesOrderId, Long companyId, Long branchId, short lineNo,
                          Long productId, String productCode, String productName,
                          Long unitId, String unitName,
                          BigDecimal qtyOrdered, BigDecimal qtyOrderedBase,
                          BigDecimal listPrice, BigDecimal unitPrice,
                          VatStatus vatStatus, BigDecimal vatRate,
                          String currency, Long createdBy) {
        this.salesOrderId = salesOrderId;
        this.companyId = companyId;
        this.branchId = branchId;
        this.lineNo = lineNo;
        this.productId = productId;
        this.productCode = productCode;
        this.productName = productName;
        this.unitId = unitId;
        this.unitName = unitName;
        this.qtyOrdered = qtyOrdered;
        this.qtyOrderedBase = qtyOrderedBase;
        this.listPriceAmount = listPrice;
        this.unitPriceAmount = unitPrice;
        this.vatStatus = vatStatus;
        this.vatRate = vatRate;
        this.currency = currency;
        this.createdBy = createdBy;
    }

    /** Derived open balance (backorder). */
    public BigDecimal openQtyBase() {
        return qtyOrderedBase.subtract(qtyFulfilledBase);
    }
}
