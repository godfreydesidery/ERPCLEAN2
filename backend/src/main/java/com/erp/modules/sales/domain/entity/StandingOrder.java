package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.StandingFrequency;
import com.erp.modules.sales.domain.enums.StandingStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * A standing (recurring) order template that generates SOs on a schedule (ADR-0029 D-8).
 * The @Scheduled job reads all ACTIVE standing orders where next_run_date <= today
 * and generates a new SalesOrder, then advances next_run_date.
 */
@Getter
@Entity
@Table(name = "standing_orders")
public class StandingOrder extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** System-assigned STD-#### number. */
    @Column(name = "order_number", nullable = false, length = 30)
    @Setter
    private String orderNumber;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    private StandingFrequency frequency;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    @Setter
    private LocalDate endDate;

    /** Next scheduled generation date; updated after each run. */
    @Column(name = "next_run_date", nullable = false)
    @Setter
    private LocalDate nextRunDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private StandingStatus status = StandingStatus.ACTIVE;

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

    protected StandingOrder() {}

    public StandingOrder(Long companyId, Long branchId, Long customerId,
                         String currency, StandingFrequency frequency,
                         LocalDate startDate, LocalDate endDate, Long createdBy) {
        this.companyId  = companyId;
        this.branchId   = branchId;
        this.customerId = customerId;
        this.currency   = currency;
        this.frequency  = frequency;
        this.startDate  = startDate;
        this.endDate    = endDate;
        this.nextRunDate = startDate;
        this.createdBy  = createdBy;
    }
}
