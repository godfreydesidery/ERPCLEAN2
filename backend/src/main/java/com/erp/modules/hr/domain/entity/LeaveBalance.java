package com.erp.modules.hr.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Per-employee, per-type, per-year leave balance (ADR-0032 D-4). */
@Getter
@Entity
@Table(name = "leave_balances")
public class LeaveBalance extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private Long employeeId;

    @Column(name = "leave_type_id", nullable = false, updatable = false)
    private Long leaveTypeId;

    @Column(name = "as_of_year", nullable = false, updatable = false)
    private Short asOfYear;

    @Column(name = "entitled_days", nullable = false, precision = 9, scale = 2)
    @Setter
    private BigDecimal entitledDays = BigDecimal.ZERO;

    @Column(name = "taken_days", nullable = false, precision = 9, scale = 2)
    @Setter
    private BigDecimal takenDays = BigDecimal.ZERO;

    @Column(name = "balance_days", nullable = false, precision = 9, scale = 2)
    @Setter
    private BigDecimal balanceDays = BigDecimal.ZERO;

    // ---- Accrual / carry-forward breakdown (P2 D6, ADR-0041) — columns only; accrual posting DEFERRED ----

    @Column(name = "carried_forward_days", nullable = false, precision = 9, scale = 2)
    @Setter
    private BigDecimal carriedForwardDays = BigDecimal.ZERO;

    @Column(name = "accrued_days", nullable = false, precision = 9, scale = 2)
    @Setter
    private BigDecimal accruedDays = BigDecimal.ZERO;

    @Column(name = "pending_days", nullable = false, precision = 9, scale = 2)
    @Setter
    private BigDecimal pendingDays = BigDecimal.ZERO;

    @Column(name = "adjustment_days", nullable = false, precision = 9, scale = 2)
    @Setter
    private BigDecimal adjustmentDays = BigDecimal.ZERO;

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

    protected LeaveBalance() {}

    public LeaveBalance(Long companyId, Long employeeId, Long leaveTypeId, short asOfYear,
                        BigDecimal entitledDays, Long createdBy) {
        this.companyId     = companyId;
        this.employeeId    = employeeId;
        this.leaveTypeId   = leaveTypeId;
        this.asOfYear      = asOfYear;
        this.entitledDays  = entitledDays;
        this.balanceDays   = entitledDays;
        this.createdBy     = createdBy;
    }
}
