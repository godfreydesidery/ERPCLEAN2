package com.erp.modules.sales.domain.entity;

import com.erp.modules.products.domain.enums.PriceSource;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.sales.domain.enums.FulfilmentMode;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
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
    private CurrencyCode currency;

    // --- projects (ADR-0033 D-3, V66) ---
    @Column(name = "project_id")
    @Setter
    private Long projectId;

    @Column(name = "project_task_id")
    @Setter
    private Long projectTaskId;

    // --- sales-depth (ADR-0029 D-3/D-4, V42/V44) --- additive columns ---

    /** How this line is fulfilled: OWN_STOCK (default) or DROP_SHIP (ADR-0029 D-3). */
    @Enumerated(EnumType.STRING)
    @Column(name = "fulfilment_mode", nullable = false, length = 20)
    @Setter
    private FulfilmentMode fulfilmentMode = FulfilmentMode.OWN_STOCK;

    /** FK → suppliers.id; only set when fulfilment_mode = DROP_SHIP. */
    @Column(name = "dropship_supplier_id")
    @Setter
    private Long dropshipSupplierId;

    /** UID of the auto-raised PO for a drop-ship line; populated by DropshipService. */
    @Column(name = "dropship_po_uid", length = 26)
    @Setter
    private String dropshipPoUid;

    /** Supplier unit cost used for COGS posting when dropship PO is receipted. */
    @Column(name = "dropship_unit_cost_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal dropshipUnitCostAmount;

    /** Price resolution source diagnostic (ADR-0029 D-6, V42). Nullable — set by pricing logic. */
    @Enumerated(EnumType.STRING)
    @Column(name = "price_source", length = 20)
    @Setter
    private PriceSource priceSource;

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
        this.currency = CurrencyCode.of(currency);
        this.createdBy = createdBy;
    }

    /** Derived open balance (backorder). */
    public BigDecimal openQtyBase() {
        return qtyOrderedBase.subtract(qtyFulfilledBase);
    }
}
