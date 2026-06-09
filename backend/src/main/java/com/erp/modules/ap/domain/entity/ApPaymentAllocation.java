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
