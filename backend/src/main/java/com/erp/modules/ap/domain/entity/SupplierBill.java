package com.erp.modules.ap.domain.entity;

import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * The AP sub-ledger open item — the payable behind GL 2100 (ADR-0015 D-2a).
 *
 * <p>Created DRAFT on bill entry; transitions to MATCHED (or HELD) when the 3-way match runs.
 * A matched bill posts DR Purchases / CR AP-control to GL synchronously (D-3/D-4/D-6).
 * Outstanding is maintained down by payments / debit notes; status is derived from balance.
 */
@Getter
@Entity
@Table(name = "supplier_bills")
public class SupplierBill extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    @Setter
    private Long branchId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private Long supplierId;

    @Column(name = "bill_number", length = 30)
    @Setter
    private String billNumber;

    @Column(name = "supplier_invoice_no", nullable = false, length = 60, updatable = false)
    private String supplierInvoiceNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private SupplierBillSource source;

    @Column(name = "purchase_order_uid", length = 26, updatable = false)
    private String purchaseOrderUid;

    @Column(name = "bill_date", nullable = false, updatable = false)
    private LocalDate billDate;

    @Column(name = "due_date", nullable = false)
    @Setter
    private LocalDate dueDate;

    /** P2: VAT tax-point (supply) date. Nullable. */
    @Column(name = "tax_point_date")
    @Setter
    private LocalDate taxPointDate;

    /** P2: date the bill was physically received. Nullable. */
    @Column(name = "received_date")
    @Setter
    private LocalDate receivedDate;

    /** Soft-FK → payment_terms(id) (P2 D1, ADR-0041). Resolved + stored at post. Nullable. */
    @Column(name = "payment_terms_id")
    @Setter
    private Long paymentTermsId;

    /** P2 D1: settlement (early-payment) discount deadline; computed at post from the terms. Nullable. */
    @Column(name = "settlement_discount_due_date")
    @Setter
    private LocalDate settlementDiscountDueDate;

    /** P2 D1: settlement (early-payment) discount amount; data-only, no GL leg (ADR-0041). Nullable. */
    @Column(name = "settlement_discount_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal settlementDiscountAmount;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal netAmount;

    @Column(name = "vat_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal vatAmount;

    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal grossAmount;

    @Column(name = "outstanding_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal outstandingAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    @Setter
    private SupplierBillStatus status = SupplierBillStatus.DRAFT;

    /**
     * Captured beneficiary account uid (soft-FK to supplier_bank_accounts.uid, no DB FK — D-4).
     * Set at bill entry; AP service validates ownership at that point.
     */
    @Column(name = "supplier_bank_account_uid", length = 26)
    @Setter
    private String supplierBankAccountUid;

    // -------------------------------------------------------------------------
    // D-7 — WHT plan/snapshot at bill entry (ADR-0040 D-7)
    // Non-posting: records the intended WHT type and amounts for informational / approval purposes.
    // Actual WHT capture happens at payment time via WhtCaptureService.
    // -------------------------------------------------------------------------

    /** Scalar ref to wht_types(id) — no DB FK (cross-module soft ref). Nullable. */
    @Column(name = "wht_type_id")
    @Setter
    private Long whtTypeId;

    /** Taxable base for WHT calculation (plan/snapshot). Nullable. */
    @Column(name = "wht_taxable_base", precision = 19, scale = 4)
    @Setter
    private BigDecimal whtTaxableBase;

    /** Planned/snapshot WHT amount. Nullable. Non-negative when set. */
    @Column(name = "wht_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal whtAmount;

    @Column(name = "posted_gl_entry_uid", length = 26)
    @Setter
    private String postedGlEntryUid;

    @Column(name = "matched_at")
    @Setter
    private Instant matchedAt;

    @Column(name = "matched_by")
    @Setter
    private Long matchedBy;

    // --- cost-centre (ADR-0025 D-6, V28) — header-level dimension default ---
    // Nullable. When set, BillMatchServiceImpl.postMatchedBillToGl stamps LineDrafts.
    /** FK → dimension_values(id); Cost Centre dimension default (nullable). */
    @Column(name = "cost_centre_value_id")
    @Setter
    private Long costCentreValueId;

    /** FK → dimension_values(id); Department dimension default (nullable). */
    @Column(name = "department_value_id")
    @Setter
    private Long departmentValueId;

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

    // -------------------------------------------------------------------------
    // ADR-0036 D-4 — FX base triple (V78). Stamped at match/post, immutable thereafter.
    // base_gross_amount is immutable (the original posted base value; BR-CUR-05).
    // base_outstanding_amount moves with outstanding_amount.
    // -------------------------------------------------------------------------

    /** Rate at match/post (units of base per 1 foreign unit; immutable; DEFAULT 1). */
    @Column(name = "fx_rate", nullable = false, precision = 19, scale = 8, updatable = false)
    @Setter
    private BigDecimal fxRate = BigDecimal.ONE;

    /** Gross amount in base currency at match/post (immutable). */
    @Column(name = "base_gross_amount", precision = 19, scale = 4, updatable = false)
    @Setter
    private BigDecimal baseGrossAmount;

    /** Outstanding amount in base currency. Decremented when payments/debit notes reduce it. */
    @Column(name = "base_outstanding_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal baseOutstandingAmount;

    /** Timestamp when rate was stamped (immutable). */
    @Column(name = "rate_at", updatable = false)
    @Setter
    private Instant rateAt;

    protected SupplierBill() {
        // JPA
    }

    public SupplierBill(Long companyId, Long branchId, Long supplierId,
                        String supplierInvoiceNo, SupplierBillSource source, String purchaseOrderUid,
                        LocalDate billDate, LocalDate dueDate,
                        BigDecimal netAmount, BigDecimal vatAmount, BigDecimal grossAmount,
                        String currency, Long createdBy) {
        this.companyId         = companyId;
        this.branchId          = branchId;
        this.supplierId        = supplierId;
        this.supplierInvoiceNo = supplierInvoiceNo;
        this.source            = source;
        this.purchaseOrderUid  = purchaseOrderUid;
        this.billDate          = billDate;
        this.dueDate           = dueDate;
        this.netAmount         = netAmount;
        this.vatAmount         = vatAmount;
        this.grossAmount       = grossAmount;
        this.outstandingAmount = BigDecimal.ZERO; // starts zero; set to gross on match/post
        this.currency          = CurrencyCode.of(currency);
        this.createdBy         = createdBy;
    }
}
