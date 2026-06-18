package com.erp.modules.ap.domain.entity;

import com.erp.modules.ap.domain.enums.PaymentRunStatus;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
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
 * Lightweight master grouping for a bulk AP payment run (ADR-0041 D3).
 *
 * <p>Member {@link ApPayment} rows carry {@code payment_run_id} pointing here (soft-FK).
 * The run itself posts nothing to GL — its member payments do; the run is a reporting/grouping
 * envelope with a DRAFT→POSTED lifecycle and a running {@code total_amount}.
 */
@Getter
@Entity
@Table(name = "payment_runs")
public class PaymentRun extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    @Setter
    private Long branchId;

    @Column(name = "run_number", nullable = false, length = 30, updatable = false)
    private String runNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private PaymentRunStatus status = PaymentRunStatus.DRAFT;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "currency", length = 3)
    @Setter
    private CurrencyCode currency;

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

    protected PaymentRun() {
        // JPA
    }

    public PaymentRun(Long companyId, Long branchId, String runNumber,
                      String currency, Long createdBy) {
        this.companyId  = companyId;
        this.branchId   = branchId;
        this.runNumber  = runNumber;
        this.currency   = CurrencyCode.ofNullable(currency);
        this.createdBy  = createdBy;
    }
}
