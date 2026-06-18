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

/** Immutable payslip document per employee per posted run (ADR-0032 D-6). */
@Getter
@Entity
@Table(name = "payslips")
public class Payslip extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "payroll_run_id", nullable = false, updatable = false)
    private Long payrollRunId;

    @Column(name = "payroll_line_id", nullable = false, updatable = false)
    private Long payrollLineId;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private Long employeeId;

    @Column(name = "payslip_number", nullable = false, length = 30)
    private String payslipNumber;

    @Column(name = "pay_date", nullable = false, updatable = false)
    private LocalDate payDate;

    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal grossAmount;

    @Column(name = "deduction_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal deductionAmount;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmount;

    @Column(name = "employer_cost_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal employerCostAmount;

    @Column(name = "ytd_gross", nullable = false, precision = 19, scale = 4)
    private BigDecimal ytdGross;

    @Column(name = "ytd_paye", nullable = false, precision = 19, scale = 4)
    private BigDecimal ytdPaye;

    @Column(name = "ytd_nssf_employee", nullable = false, precision = 19, scale = 4)
    private BigDecimal ytdNssfEmployee;

    @Column(name = "ytd_net", nullable = false, precision = 19, scale = 4)
    private BigDecimal ytdNet;

    /** YTD total deductions (P3, additive; defaults 0, set post-construction). */
    @Column(name = "ytd_deduction", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal ytdDeduction = BigDecimal.ZERO;

    /** YTD employer cost (P3, additive; defaults 0, set post-construction). */
    @Column(name = "ytd_employer_cost", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal ytdEmployerCost = BigDecimal.ZERO;

    /** documents-module uid of the rendered payslip PDF (P3). */
    @Column(name = "generated_pdf_document_uid", length = 26)
    @Setter
    private String generatedPdfDocumentUid;

    /** Delivery (any channel) timestamp (P3). */
    @Column(name = "delivered_at")
    @Setter
    private Instant deliveredAt;

    /** Email-delivery timestamp (P3). */
    @Column(name = "emailed_at")
    @Setter
    private Instant emailedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected Payslip() {}

    public Payslip(Long companyId, Long payrollRunId, Long payrollLineId, Long employeeId,
                   String payslipNumber, LocalDate payDate,
                   BigDecimal grossAmount, BigDecimal deductionAmount, BigDecimal netAmount,
                   BigDecimal employerCostAmount,
                   BigDecimal ytdGross, BigDecimal ytdPaye, BigDecimal ytdNssfEmployee,
                   BigDecimal ytdNet, Long createdBy) {
        this.companyId          = companyId;
        this.payrollRunId       = payrollRunId;
        this.payrollLineId      = payrollLineId;
        this.employeeId         = employeeId;
        this.payslipNumber      = payslipNumber;
        this.payDate            = payDate;
        this.grossAmount        = grossAmount;
        this.deductionAmount    = deductionAmount;
        this.netAmount          = netAmount;
        this.employerCostAmount = employerCostAmount;
        this.ytdGross           = ytdGross;
        this.ytdPaye            = ytdPaye;
        this.ytdNssfEmployee    = ytdNssfEmployee;
        this.ytdNet             = ytdNet;
        this.createdBy          = createdBy;
    }
}
