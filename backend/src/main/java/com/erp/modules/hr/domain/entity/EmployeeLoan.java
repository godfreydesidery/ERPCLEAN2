package com.erp.modules.hr.domain.entity;

import com.erp.modules.hr.domain.enums.LoanStatus;
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

/** Employee loan / advance record (ADR-0032 D-4). */
@Getter
@Entity
@Table(name = "employee_loans")
public class EmployeeLoan extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private Long employeeId;

    @Column(name = "loan_number", nullable = false, length = 30)
    private String loanNumber;

    @Column(name = "principal_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal principalAmount;

    @Column(name = "installment_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal installmentAmount;

    @Column(name = "outstanding_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal outstandingAmount;

    @Column(name = "gl_account_id", nullable = false, updatable = false)
    private Long glAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Setter
    private LoanStatus status = LoanStatus.ACTIVE;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

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

    protected EmployeeLoan() {}

    public EmployeeLoan(Long companyId, Long employeeId, String loanNumber,
                        BigDecimal principalAmount, BigDecimal installmentAmount,
                        Long glAccountId, LocalDate startDate, String currency, Long createdBy) {
        this.companyId         = companyId;
        this.employeeId        = employeeId;
        this.loanNumber        = loanNumber;
        this.principalAmount   = principalAmount;
        this.installmentAmount = installmentAmount;
        this.outstandingAmount = principalAmount;
        this.glAccountId       = glAccountId;
        this.startDate         = startDate;
        this.currency          = currency;
        this.createdBy         = createdBy;
    }
}
