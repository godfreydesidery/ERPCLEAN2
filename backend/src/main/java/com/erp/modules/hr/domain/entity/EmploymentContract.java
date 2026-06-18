package com.erp.modules.hr.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.modules.hr.domain.enums.ContractType;
import com.erp.modules.hr.domain.enums.PayFrequency;
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

/** Employment contract — at most one active per employee (ADR-0032 D-4, BR-HR-02). */
@Getter
@Entity
@Table(name = "employment_contracts")
public class EmploymentContract extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type", nullable = false, length = 16)
    @Setter
    private ContractType contractType;

    @Column(name = "base_salary_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal baseSalaryAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_frequency", nullable = false, length = 12)
    private PayFrequency payFrequency = PayFrequency.MONTHLY;

    @Column(name = "start_date", nullable = false, updatable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    @Setter
    private LocalDate endDate;

    @Column(name = "paye_resident", nullable = false)
    @Setter
    private boolean payeResident = true;

    @Column(name = "nssf_member", nullable = false)
    @Setter
    private boolean nssfMember = true;

    @Column(name = "heslb_borrower", nullable = false)
    @Setter
    private boolean heslbBorrower = false;

    @Column(name = "wcf_covered", nullable = false)
    @Setter
    private boolean wcfCovered = true;

    @Column(name = "sdl_counted", nullable = false)
    @Setter
    private boolean sdlCounted = true;

    // ---- Contract policy fields (P2 D6, ADR-0041) ----

    @Column(name = "probation_months")
    @Setter
    private Integer probationMonths;

    @Column(name = "notice_period_days")
    @Setter
    private Integer noticePeriodDays;

    @Column(name = "working_hours_per_day", precision = 5, scale = 2)
    @Setter
    private BigDecimal workingHoursPerDay;

    @Column(name = "working_days_per_week")
    @Setter
    private Integer workingDaysPerWeek;

    @Column(name = "job_grade", length = 40)
    @Setter
    private String jobGrade;

    @Column(name = "signed_document_ref", length = 255)
    @Setter
    private String signedDocumentRef;

    @Column(name = "active", nullable = false)
    @Setter
    private boolean active = true;

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

    protected EmploymentContract() {}

    public EmploymentContract(Long companyId, Long employeeId, ContractType contractType,
                               BigDecimal baseSalaryAmount, String currency,
                               LocalDate startDate, LocalDate endDate, Long createdBy) {
        this.companyId         = companyId;
        this.employeeId        = employeeId;
        this.contractType      = contractType;
        this.baseSalaryAmount  = baseSalaryAmount;
        this.currency          = CurrencyCode.ofNullable(currency);
        this.startDate         = startDate;
        this.endDate           = endDate;
        this.createdBy         = createdBy;
    }
}
