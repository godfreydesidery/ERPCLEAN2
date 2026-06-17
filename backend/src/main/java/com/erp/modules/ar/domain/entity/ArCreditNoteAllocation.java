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
 * Junction: credit note ↔ open item (ADR-0040 D-6). No uid (no external URL). Append-only —
 * reapply is delete + re-insert; allocation itself posts nothing to GL except the realized-FX
 * plug per allocation when settlement_rate differs from invoice rate (mirrors AR receipt).
 */
@Getter
@Entity
@Table(name = "ar_credit_note_allocations")
public class ArCreditNoteAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "credit_note_id", nullable = false, updatable = false)
    private Long creditNoteId;

    @Column(name = "ar_invoice_id", nullable = false, updatable = false)
    private Long arInvoiceId;

    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal allocatedAmount;

    /**
     * Allocated amount in base currency at the CN's settlement rate.
     * Used for balance netting and base_unapplied_amount decrement.
     */
    @Column(name = "base_allocated_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal baseAllocatedAmount;

    /**
     * The CN's fx_rate at the time of allocation (snapshot, immutable).
     * The realized-FX plug = invoice_rate vs settlement_rate, same mechanics as receipt allocation.
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

    protected ArCreditNoteAllocation() {
        // JPA
    }

    public ArCreditNoteAllocation(Long companyId, Long creditNoteId, Long arInvoiceId,
                                   BigDecimal allocatedAmount, Long allocatedBy) {
        this.companyId       = companyId;
        this.creditNoteId    = creditNoteId;
        this.arInvoiceId     = arInvoiceId;
        this.allocatedAmount = allocatedAmount;
        this.allocatedBy     = allocatedBy;
        this.createdBy       = allocatedBy;
    }
}
