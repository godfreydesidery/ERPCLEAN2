package com.erp.modules.sales.domain.entity;

import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.platform.common.domain.Ulid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One product line on a sales invoice (ADR-0008 D-2, FR-SALES-04).
 *
 * <p>Does NOT extend {@link com.erp.platform.common.domain.UidEntity} — lines are add/remove
 * only (no @Version), mirroring ProductBarcode. They DO carry a uid for external addressing.
 *
 * <p>company_id and branch_id are DENORMALISED from the parent header at construction
 * (set-once, immutable — tenant predicate without join, ADR-0008 D-2).
 *
 * <p>Snapshot columns (product_code, product_name, unit_name, list_price_amount, vat_status,
 * vat_rate) are set at line-add and never re-read — the invoice prints what was sold even if
 * the product is later renamed or re-priced (FR-SALES-07/08, ADR-0008 D-3).
 */
@Getter
@Entity
@Table(name = "sales_invoice_lines")
public class SalesInvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, updatable = false, length = 26)
    private String uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private SalesInvoice invoice;

    /** Denormalised from parent invoice; set at construction; immutable. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** Denormalised from parent invoice; set at construction; immutable. */
    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** 1-based ordinal; unique per invoice (uq_sales_invoice_line_no). */
    @Column(name = "line_no", nullable = false)
    private short lineNo;

    /** FK → products.id; scalar — no cross-module @ManyToOne (ADR-0008 D-1). */
    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    /** Snapshot of product code at sale time (FR-SALES-07, ADR-0008 D-3). */
    @Column(name = "product_code", nullable = false, length = 20)
    private String productCode;

    /** Snapshot of product name at sale time. */
    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    /** FK → units_of_measure.id; scalar. */
    @Column(name = "unit_id", nullable = false, updatable = false)
    private Long unitId;

    /** Snapshot of unit name at sale time. */
    @Column(name = "unit_name", nullable = false, length = 60)
    private String unitName;

    /** Quantity in unitId units; CHECK > 0. */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal quantity;

    /** Quantity in product base unit — snapshotted for future stock-deduction outbox (ADR-0008 D-9). */
    @Column(name = "qty_in_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal qtyInBase;

    /** Snapshot of the applicable price-list net unit price (list price, FR-SALES-07). */
    @Column(name = "list_price_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal listPriceAmount;

    /** Applied net unit price (= listPrice unless overridden, FR-SALES-08). */
    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal unitPriceAmount;

    @Column(name = "price_overridden", nullable = false)
    @Setter
    private boolean priceOverridden = false;

    /** FK → app_users.id; NULL unless priceOverridden. */
    @Column(name = "overridden_by")
    @Setter
    private Long overriddenBy;

    /** Line-level discount amount (optional). Applied before VAT (FR-SALES-09). */
    @Column(name = "line_discount_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal lineDiscountAmount;

    /** Line-level discount percent (alternative entry; resolved at compute time). */
    @Column(name = "line_discount_percent", precision = 9, scale = 4)
    @Setter
    private BigDecimal lineDiscountPercent;

    /** Snapshot of product VAT status at sale time (ADR-0008 D-3/D-5). */
    @Enumerated(EnumType.STRING)
    @Column(name = "vat_status", nullable = false, length = 20)
    private VatStatus vatStatus;

    /** Snapshot of applicable VAT rate at sale time, e.g. 0.1800 (ADR-0008 D-3/D-5). */
    @Column(name = "vat_rate", nullable = false, precision = 9, scale = 4)
    private BigDecimal vatRate;

    /** Computed: (unitPrice × qty) − lineDiscount − apportioned docDiscount (ADR-0008 D-4). */
    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal netAmount = BigDecimal.ZERO;

    /** Computed: net × vatRate (0 for zero-rated/exempt). */
    @Column(name = "vat_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal vatAmount = BigDecimal.ZERO;

    /** Computed: net + vat. */
    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal grossAmount = BigDecimal.ZERO;

    /** Document currency; denormalised from parent invoice (BR-SALES-04). */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    // --- projects (ADR-0033 D-3, V66) ---
    /** FK → projects(id); nullable — analysis tag for project-billed sales. */
    @Column(name = "project_id")
    @Setter
    private Long projectId;

    /** FK → project_tasks(id); nullable. */
    @Column(name = "project_task_id")
    @Setter
    private Long projectTaskId;

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

    protected SalesInvoiceLine() {
        // JPA
    }

    public SalesInvoiceLine(SalesInvoice invoice, short lineNo,
                            Long productId, String productCode, String productName,
                            Long unitId, String unitName,
                            BigDecimal quantity, BigDecimal qtyInBase,
                            BigDecimal listPriceAmount, BigDecimal unitPriceAmount,
                            VatStatus vatStatus, BigDecimal vatRate,
                            Long createdBy) {
        this.invoice = invoice;
        this.companyId = invoice.getCompanyId();   // denormalise at construction
        this.branchId = invoice.getBranchId();      // denormalise at construction
        this.currency = invoice.getCurrency();      // denormalise at construction
        this.lineNo = lineNo;
        this.productId = productId;
        this.productCode = productCode;
        this.productName = productName;
        this.unitId = unitId;
        this.unitName = unitName;
        this.quantity = quantity;
        this.qtyInBase = qtyInBase;
        this.listPriceAmount = listPriceAmount;
        this.unitPriceAmount = unitPriceAmount;
        this.vatStatus = vatStatus;
        this.vatRate = vatRate;
        this.createdBy = createdBy;
    }
}
