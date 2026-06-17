package com.erp.modules.hr.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.modules.hr.domain.enums.PayrollLineStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** One payroll line per employee per run (ADR-0032 D-6). */
@Getter
@Entity
@Table(name = "payroll_lines")
public class PayrollLine extends UidEntity {

    @Column(name = "payroll_run_id", nullable = false, updatable = false)
    private Long payrollRunId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private Long employeeId;

    @Column(name = "employee_number", nullable = false, length = 30)
    private String employeeNumber;

    @Column(name = "employee_name", nullable = false, length = 160)
    private String employeeName;

    @Column(name = "department_name", length = 120)
    private String departmentName;

    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Column(name = "taxable_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal taxableAmount = BigDecimal.ZERO;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Column(name = "paye_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal payeAmount = BigDecimal.ZERO;

    @Column(name = "nssf_employee_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal nssfEmployeeAmount = BigDecimal.ZERO;

    @Column(name = "heslb_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal heslbAmount = BigDecimal.ZERO;

    @Column(name = "voluntary_deduction_total", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal voluntaryDeductionTotal = BigDecimal.ZERO;

    @Column(name = "loan_deduction_total", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal loanDeductionTotal = BigDecimal.ZERO;

    @Column(name = "nssf_employer_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal nssfEmployerAmount = BigDecimal.ZERO;

    @Column(name = "wcf_employer_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal wcfEmployerAmount = BigDecimal.ZERO;

    @Column(name = "sdl_employer_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal sdlEmployerAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Setter
    private PayrollLineStatus status = PayrollLineStatus.OK;

    @Column(name = "flag_reason", length = 255)
    @Setter
    private String flagReason;

    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    // ---- Payee snapshot (set at calculate time, ADR-0040 D-11) ----

    @Column(name = "payee_method", length = 20)
    @Setter
    private String payeeMethod;

    @Column(name = "payee_account_ref", length = 60)
    @Setter
    private String payeeAccountRef;

    @Column(name = "payee_bank_name", length = 120)
    @Setter
    private String payeeBankName;

    @Column(name = "payee_account_name", length = 120)
    @Setter
    private String payeeAccountName;

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

    protected PayrollLine() {}

    public PayrollLine(Long payrollRunId, Long companyId, Long employeeId,
                       String employeeNumber, String employeeName, String departmentName,
                       String currency, Long createdBy) {
        this.payrollRunId   = payrollRunId;
        this.companyId      = companyId;
        this.employeeId     = employeeId;
        this.employeeNumber = employeeNumber;
        this.employeeName   = employeeName;
        this.departmentName = departmentName;
        this.currency       = CurrencyCode.ofNullable(currency);
        this.createdBy      = createdBy;
    }
}
