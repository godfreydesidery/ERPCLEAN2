package com.erp.modules.ar.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Junction: receipt ↔ open item (ADR-0014 D-2c). No uid (no external URL). Append-only —
 * re-allocation is delete + re-insert; allocation itself posts nothing to GL (BR-AR-12).
 */
@Getter
@Entity
@Table(name = "ar_receipt_allocations")
public class ArReceiptAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "receipt_id", nullable = false, updatable = false)
    private Long receiptId;

    @Column(name = "ar_invoice_id", nullable = false, updatable = false)
    private Long arInvoiceId;

    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal allocatedAmount;

    @Column(name = "allocated_at", nullable = false, updatable = false)
    private Instant allocatedAt = Instant.now();

    @Column(name = "allocated_by")
    private Long allocatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected ArReceiptAllocation() {
        // JPA
    }

    public ArReceiptAllocation(Long companyId, Long receiptId, Long arInvoiceId,
                                BigDecimal allocatedAmount, Long allocatedBy) {
        this.companyId       = companyId;
        this.receiptId       = receiptId;
        this.arInvoiceId     = arInvoiceId;
        this.allocatedAmount = allocatedAmount;
        this.allocatedBy     = allocatedBy;
        this.createdBy       = allocatedBy;
    }
}
