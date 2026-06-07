package com.erp.modules.purchases.domain.entity;

import com.erp.modules.purchases.domain.enums.GoodsReceiptStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Goods Receipt header (ADR-0011 D-2, FR-PURCH-01b).
 *
 * <p>Extends {@link UidEntity} (id + uid + @Version optimistic lock).
 * Always recorded against a PO ({@code purchase_order_id} is NOT NULL, §9 assumption).
 * Inherits company_id, branch_id, supplier_id from its PO (BR-PURCH-01/02) — denormalised.
 *
 * <p>Lifecycle: DRAFT → RECEIVED → VOID.
 * receipt_number is NULL while DRAFT, assigned at receive (ADR-0011 D-6).
 * uid is the {@code receiptUid} in the STOCK.RECEIVED and STOCK.RECEIPT.VOIDED payloads (ADR-0011 D-8).
 */
@Getter
@Entity
@Table(name = "goods_receipts")
public class GoodsReceipt extends UidEntity {

    /** FK → companies.id; = PO's company; tenant scope; never updated (BR-PURCH-01). */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** FK → branches.id; = PO's branch; never updated (BR-PURCH-01). */
    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** FK → purchase_orders.id; the PO this GR receives against; never updated. */
    @Column(name = "purchase_order_id", nullable = false, updatable = false)
    private Long purchaseOrderId;

    /** GRN-####; assigned at receive; NULL while DRAFT (ADR-0011 D-6). */
    @Column(name = "receipt_number", length = 30)
    @Setter
    private String receiptNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private GoodsReceiptStatus status = GoodsReceiptStatus.DRAFT;

    /** FK → suppliers.id; inherited from PO (denormalised for reporting, BR-PURCH-02). */
    @Column(name = "supplier_id", nullable = false)
    private Long supplierId;

    /** Set at receive (DRAFT → RECEIVED); the receivedAt in STOCK.RECEIVED payload (ADR-0011 D-8). */
    @Column(name = "received_at")
    @Setter
    private Instant receivedAt;

    /** FK → app_users.id; the storekeeper who received. */
    @Column(name = "received_by")
    @Setter
    private Long receivedBy;

    @Column(name = "voided_at")
    @Setter
    private Instant voidedAt;

    @Column(name = "voided_by")
    @Setter
    private Long voidedBy;

    @Column(name = "void_reason", length = 255)
    @Setter
    private String voidReason;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

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

    protected GoodsReceipt() {
        // JPA
    }

    public GoodsReceipt(Long companyId, Long branchId, Long purchaseOrderId, Long supplierId,
                        Long createdBy) {
        this.companyId       = companyId;
        this.branchId        = branchId;
        this.purchaseOrderId = purchaseOrderId;
        this.supplierId      = supplierId;
        this.createdBy       = createdBy;
    }
}
