package com.erp.modules.hr.domain.entity;

import com.erp.modules.hr.domain.enums.LoanInstallmentStatus;
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

/** Loan installment schedule row (ADR-0032 D-4). */
@Getter
@Entity
@Table(name = "employee_loan_installments")
public class EmployeeLoanInstallment extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "employee_loan_id", nullable = false, updatable = false)
    private Long employeeLoanId;

    @Column(name = "installment_no", nullable = false, updatable = false)
    private Short installmentNo;

    @Column(name = "due_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal dueAmount;

    @Column(name = "due_period", nullable = false, length = 7)
    private String duePeriod;

    @Column(name = "deducted_in_run_uid", length = 26)
    @Setter
    private String deductedInRunUid;

    /** Actual amount deducted at run time (P2-M5 snapshot). */
    @Column(name = "deducted_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal deductedAmount;

    /** Scheduled due date for this installment (P2-M5). */
    @Column(name = "due_date")
    @Setter
    private LocalDate dueDate;

    /** Timestamp the installment was deducted/paid (P2-M5). */
    @Column(name = "paid_at")
    @Setter
    private Instant paidAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Setter
    private LoanInstallmentStatus status = LoanInstallmentStatus.PENDING;

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

    protected EmployeeLoanInstallment() {}

    public EmployeeLoanInstallment(Long companyId, Long employeeLoanId, short installmentNo,
                                    BigDecimal dueAmount, String duePeriod, Long createdBy) {
        this.companyId      = companyId;
        this.employeeLoanId = employeeLoanId;
        this.installmentNo  = installmentNo;
        this.dueAmount      = dueAmount;
        this.duePeriod      = duePeriod;
        this.createdBy      = createdBy;
    }
}
