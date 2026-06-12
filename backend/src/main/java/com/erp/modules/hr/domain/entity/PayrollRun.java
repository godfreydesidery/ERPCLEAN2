package com.erp.modules.hr.domain.entity;

import com.erp.modules.hr.domain.enums.PayrollRunStatus;
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

/** Payroll run header (ADR-0032 D-6). */
@Getter
@Entity
@Table(name = "payroll_runs")
public class PayrollRun extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    @Setter
    private Long branchId;

    @Column(name = "run_number", nullable = false, length = 30)
    private String runNumber;

    @Column(name = "period_year", nullable = false, updatable = false)
    private Short periodYear;

    @Column(name = "period_month", nullable = false, updatable = false)
    private Short periodMonth;

    @Column(name = "pay_date", nullable = false, updatable = false)
    private LocalDate payDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Setter
    private PayrollRunStatus status = PayrollRunStatus.DRAFT;

    @Column(name = "gross_total", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal grossTotal = BigDecimal.ZERO;

    @Column(name = "deduction_total", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal deductionTotal = BigDecimal.ZERO;

    @Column(name = "net_total", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal netTotal = BigDecimal.ZERO;

    @Column(name = "employer_cost_total", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal employerCostTotal = BigDecimal.ZERO;

    @Column(name = "calculated_at")
    @Setter
    private Instant calculatedAt;

    @Column(name = "approved_at")
    @Setter
    private Instant approvedAt;

    @Column(name = "posted_at")
    @Setter
    private Instant postedAt;

    @Column(name = "paid_at")
    @Setter
    private Instant paidAt;

    @Column(name = "reversed_at")
    @Setter
    private Instant reversedAt;

    @Column(name = "approved_by")
    @Setter
    private Long approvedBy;

    @Column(name = "posted_by")
    @Setter
    private Long postedBy;

    @Column(name = "gl_entry_uid", length = 26)
    @Setter
    private String glEntryUid;

    @Column(name = "reversal_of_run_uid", length = 26)
    @Setter
    private String reversalOfRunUid;

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

    protected PayrollRun() {}

    public PayrollRun(Long companyId, Long branchId, String runNumber,
                      short periodYear, short periodMonth, LocalDate payDate, Long createdBy) {
        this.companyId   = companyId;
        this.branchId    = branchId;
        this.runNumber   = runNumber;
        this.periodYear  = periodYear;
        this.periodMonth = periodMonth;
        this.payDate     = payDate;
        this.createdBy   = createdBy;
    }
}
