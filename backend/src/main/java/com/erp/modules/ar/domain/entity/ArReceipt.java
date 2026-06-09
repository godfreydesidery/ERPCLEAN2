package com.erp.modules.ar.domain.entity;

import com.erp.modules.ar.domain.enums.ArReceiptStatus;
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
 * A customer receipt (RCT-####). The cash leg posts once to GL at creation (ADR-0014 D-4).
 * Allocation detail lives in {@link ArReceiptAllocation}; the receipt's unallocated_amount
 * tracks the on-account remainder.
 */
@Getter
@Entity
@Table(name = "ar_receipts")
public class ArReceipt extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    @Setter
    private Long branchId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "receipt_number", nullable = false, length = 30, updatable = false)
    private String receiptNumber;

    @Column(name = "receipt_date", nullable = false, updatable = false)
    private LocalDate receiptDate;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "unallocated_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal unallocatedAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "tender_type", nullable = false, length = 20, updatable = false)
    private String tenderType;

    @Column(name = "bank_reference", length = 80)
    @Setter
    private String bankReference;

    /** Scalar uid of the GL journal entry (no FK — cross-module link, ADR-0014 D-11). */
    @Column(name = "gl_entry_uid", length = 26)
    @Setter
    private String glEntryUid;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private ArReceiptStatus status = ArReceiptStatus.UNALLOCATED;

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

    protected ArReceipt() {
        // JPA
    }

    public ArReceipt(Long companyId, Long branchId, Long customerId,
                     String receiptNumber, LocalDate receiptDate,
                     BigDecimal amount, String currency, String tenderType, Long createdBy) {
        this.companyId         = companyId;
        this.branchId          = branchId;
        this.customerId        = customerId;
        this.receiptNumber     = receiptNumber;
        this.receiptDate       = receiptDate;
        this.amount            = amount;
        this.unallocatedAmount = amount;
        this.currency          = currency;
        this.tenderType        = tenderType;
        this.createdBy         = createdBy;
    }
}
