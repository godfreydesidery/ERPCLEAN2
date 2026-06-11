package com.erp.modules.ap.domain.entity;

import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.platform.common.domain.UidEntity;
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
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    @Setter
    private SupplierBillStatus status = SupplierBillStatus.DRAFT;

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
        this.currency          = currency;
        this.createdBy         = createdBy;
    }
}
