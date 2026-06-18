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
 * Junction: ap_payment ↔ supplier_bill (ADR-0015 D-2e).
 * No uid (junction table, per convention). append-only.
 */
@Getter
@Entity
@Table(name = "ap_payment_allocations")
public class ApPaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "ap_payment_id", nullable = false, updatable = false)
    private Long apPaymentId;

    @Column(name = "supplier_bill_id", nullable = false, updatable = false)
    private Long supplierBillId;

    @Column(name = "allocated_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal allocatedAmount;

    // ADR-0036 D-4 — per-allocation base capture (V78, graft from Proposal B).
    // base_allocated_amount : face allocated × settlement rate, HALF_UP (Tranche 3 realized-FX).
    // settlement_rate       : the payment's fx_rate at the time of allocation (immutable snapshot).

    /** Allocated amount in base currency at the settlement rate. */
    @Column(name = "base_allocated_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal baseAllocatedAmount;

    /** Settlement exchange rate (units of base per 1 foreign unit) used for this allocation. */
    @Column(name = "settlement_rate", precision = 19, scale = 8)
    @Setter
    private BigDecimal settlementRate;

    /** P2: when this allocation was made (mirrors AR allocation). Nullable. */
    @Column(name = "allocated_at")
    @Setter
    private Instant allocatedAt;

    /** P2: actor who made this allocation (mirrors AR allocation). Nullable. */
    @Column(name = "allocated_by")
    @Setter
    private Long allocatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected ApPaymentAllocation() {
        // JPA
    }

    public ApPaymentAllocation(Long companyId, Long apPaymentId, Long supplierBillId,
                                BigDecimal allocatedAmount, Long createdBy) {
        this.companyId       = companyId;
        this.apPaymentId     = apPaymentId;
        this.supplierBillId  = supplierBillId;
        this.allocatedAmount = allocatedAmount;
        this.createdBy       = createdBy;
    }
}
