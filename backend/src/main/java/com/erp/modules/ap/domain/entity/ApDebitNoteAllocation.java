package com.erp.modules.ap.domain.entity;

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
 * Junction: debit note ↔ open bill (ADR-0041 D3). No uid (no external URL). Append-only —
 * reapply is delete + re-insert; allocation itself posts nothing to GL except the realized-FX
 * plug per allocation when settlement_rate differs from bill rate. Mirrors
 * {@link com.erp.modules.ar.domain.entity.ArCreditNoteAllocation} exactly.
 */
@Getter
@Entity
@Table(name = "ap_debit_note_allocations")
public class ApDebitNoteAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "debit_note_id", nullable = false, updatable = false)
    private Long debitNoteId;

    @Column(name = "supplier_bill_id", nullable = false, updatable = false)
    private Long supplierBillId;

    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal allocatedAmount;

    /**
     * Allocated amount in base currency at the DN's settlement rate.
     * Used for balance netting and base_unapplied_amount decrement.
     */
    @Column(name = "base_allocated_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal baseAllocatedAmount;

    /**
     * The DN's fx_rate at the time of allocation (snapshot, immutable).
     * The realized-FX plug = bill_rate vs settlement_rate, same mechanics as payment allocation.
     */
    @Column(name = "settlement_rate", precision = 19, scale = 8)
    @Setter
    private BigDecimal settlementRate;

    @Column(name = "allocated_at", nullable = false, updatable = false)
    private Instant allocatedAt = Instant.now();

    @Column(name = "allocated_by")
    private Long allocatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected ApDebitNoteAllocation() {
        // JPA
    }

    public ApDebitNoteAllocation(Long companyId, Long debitNoteId, Long supplierBillId,
                                 BigDecimal allocatedAmount, Long allocatedBy) {
        this.companyId       = companyId;
        this.debitNoteId     = debitNoteId;
        this.supplierBillId  = supplierBillId;
        this.allocatedAmount = allocatedAmount;
        this.allocatedBy     = allocatedBy;
        this.createdBy       = allocatedBy;
    }
}
