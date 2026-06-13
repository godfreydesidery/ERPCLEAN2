package com.erp.modules.purchases.domain.entity;

import com.erp.modules.purchases.domain.enums.PurchaseReturnStatus;
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
 * Purchase Return / RMA header (ADR-0027 D-7).
 * PRET-#### assigned at create (D-10). Immutable once CONFIRMED (BR-PROC-12).
 */
@Getter
@Entity
@Table(name = "purchase_returns")
public class PurchaseReturn extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "return_number", nullable = false, length = 30)
    private String returnNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private PurchaseReturnStatus status = PurchaseReturnStatus.DRAFT;

    @Column(name = "goods_receipt_id", nullable = false, updatable = false)
    private Long goodsReceiptId;

    @Column(name = "goods_receipt_uid", nullable = false, length = 26, updatable = false)
    private String goodsReceiptUid;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private Long supplierId;

    @Column(name = "supplier_code", nullable = false, length = 20)
    private String supplierCode;

    @Column(name = "supplier_name", nullable = false, length = 200)
    private String supplierName;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

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

    /** Scalar uid of the AP debit note raised on confirm. */
    @Column(name = "debit_note_uid", length = 26)
    @Setter
    private String debitNoteUid;

    /** Stock-out reversal journal uid (diagnostic). */
    @Column(name = "gl_entry_uid", length = 26)
    @Setter
    private String glEntryUid;

    @Column(name = "confirmed_at")
    @Setter
    private Instant confirmedAt;

    @Column(name = "confirmed_by")
    @Setter
    private Long confirmedBy;

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

    protected PurchaseReturn() {
        // JPA
    }

    public PurchaseReturn(Long companyId, Long branchId, String returnNumber,
                          Long goodsReceiptId, String goodsReceiptUid,
                          Long supplierId, String supplierCode, String supplierName,
                          String reason, String currency, Long createdBy) {
        this.companyId        = companyId;
        this.branchId         = branchId;
        this.returnNumber     = returnNumber;
        this.goodsReceiptId   = goodsReceiptId;
        this.goodsReceiptUid  = goodsReceiptUid;
        this.supplierId       = supplierId;
        this.supplierCode     = supplierCode;
        this.supplierName     = supplierName;
        this.reason           = reason;
        this.currency         = currency;
        this.createdBy        = createdBy;
    }
}
