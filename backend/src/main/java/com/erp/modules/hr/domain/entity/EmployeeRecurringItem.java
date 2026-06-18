package com.erp.modules.hr.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** Per-employee recurring pay item (ADR-0032 D-4). */
@Getter
@Entity
@Table(name = "employee_recurring_items")
public class EmployeeRecurringItem extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private Long employeeId;

    @Column(name = "pay_component_id", nullable = false, updatable = false)
    private Long payComponentId;

    @Column(name = "amount_or_percent", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal amountOrPercent;

    /** When true, {@code amountOrPercent} is a percentage rather than an absolute amount (P3). */
    @Column(name = "is_percent", nullable = false)
    @Setter
    private boolean percent = false;

    /** Optional cap on the computed amount (P3). */
    @Column(name = "cap_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal capAmount;

    @Column(name = "note", length = 255)
    @Setter
    private String note;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    @Setter
    private LocalDate effectiveTo;

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

    protected EmployeeRecurringItem() {}

    public EmployeeRecurringItem(Long companyId, Long employeeId, Long payComponentId,
                                  BigDecimal amountOrPercent, LocalDate effectiveFrom,
                                  LocalDate effectiveTo, Long createdBy) {
        this.companyId       = companyId;
        this.employeeId      = employeeId;
        this.payComponentId  = payComponentId;
        this.amountOrPercent = amountOrPercent;
        this.effectiveFrom   = effectiveFrom;
        this.effectiveTo     = effectiveTo;
        this.createdBy       = createdBy;
    }
}
