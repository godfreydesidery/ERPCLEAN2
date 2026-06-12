package com.erp.modules.purchases.domain.entity;

import com.erp.modules.purchases.domain.enums.PurchaseOrderStatus;
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
 * Purchase Order header (ADR-0011 D-2, FR-PURCH-01a).
 *
 * <p>Extends {@link UidEntity} (id + uid + @Version optimistic lock).
 * company_id and branch_id are scalar Longs — no cross-module @ManyToOne (ADR-0011 D-1).
 * supplier_id is a scalar FK — DB FK only; no JPA association (ADR-0011 D-1).
 *
 * <p>Lifecycle: DRAFT → ORDERED → PARTIALLY_RECEIVED → RECEIVED → CLOSED / VOID.
 * Lines are immutable once ORDERED (BR-PURCH-05). order_number is NULL while DRAFT (ADR-0011 D-6).
 * Snapshot columns (supplier_code, supplier_name) are set at order-placement.
 */
@Getter
@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder extends UidEntity {

    /** FK → companies.id; tenant scope; never updated (BR-PURCH-01). */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** FK → branches.id; the branch the PO was raised at; never updated (BR-PURCH-01). */
    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** PO-####; assigned at order-placement; NULL while DRAFT (ADR-0011 D-6). */
    @Column(name = "order_number", length = 30)
    @Setter
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    @Setter
    private PurchaseOrderStatus status = PurchaseOrderStatus.DRAFT;

    /** FK → suppliers.id; scalar (ADR-0011 D-1). */
    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    /** Snapshot of supplier code at order-placement (immutable record). */
    @Column(name = "supplier_code", nullable = false, length = 20)
    @Setter
    private String supplierCode;

    /** Snapshot of supplier name at order-placement. */
    @Column(name = "supplier_name", nullable = false, length = 200)
    @Setter
    private String supplierName;

    /** Document currency (ISO 4217). All Money on this PO shares this currency (BR-PURCH-04). */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Computed roll-up: Σ line totals (ADR-0011 D-5). DEFAULT 0. */
    @Column(name = "order_total_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal orderTotalAmount = BigDecimal.ZERO;

    /** Optional expected-delivery date (operational convenience). */
    @Column(name = "expected_date")
    @Setter
    private LocalDate expectedDate;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

    @Column(name = "ordered_at")
    @Setter
    private Instant orderedAt;

    /** FK → app_users.id; who placed the order. */
    @Column(name = "ordered_by")
    @Setter
    private Long orderedBy;

    @Column(name = "closed_at")
    @Setter
    private Instant closedAt;

    @Column(name = "closed_by")
    @Setter
    private Long closedBy;

    @Column(name = "voided_at")
    @Setter
    private Instant voidedAt;

    @Column(name = "voided_by")
    @Setter
    private Long voidedBy;

    @Column(name = "void_reason", length = 255)
    @Setter
    private String voidReason;

    // --- sales-depth (ADR-0029 D-3, V44) — drop-ship seam fields ---
    /** FK → customers.id; set only on a drop-ship PO (ship directly to this customer). Nullable. */
    @Column(name = "ship_to_customer_id")
    @Setter
    private Long shipToCustomerId;

    /** UID of the sales order line that triggered this drop-ship PO. Nullable. */
    @Column(name = "source_sales_order_uid", length = 26)
    @Setter
    private String sourceSalesOrderUid;

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

    protected PurchaseOrder() {
        // JPA
    }

    public PurchaseOrder(Long companyId, Long branchId, Long supplierId,
                         String supplierCode, String supplierName,
                         String currency, Long createdBy) {
        this.companyId    = companyId;
        this.branchId     = branchId;
        this.supplierId   = supplierId;
        this.supplierCode = supplierCode;
        this.supplierName = supplierName;
        this.currency     = currency;
        this.createdBy    = createdBy;
    }
}
