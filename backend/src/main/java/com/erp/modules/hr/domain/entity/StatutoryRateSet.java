package com.erp.modules.hr.domain.entity;

import com.erp.modules.hr.domain.enums.StatutoryRateType;
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

/** Effective-dated statutory rate set (NSSF/WCF/SDL/HESLB) — append-only (ADR-0032 D-3). */
@Getter
@Entity
@Table(name = "statutory_rate_sets")
public class StatutoryRateSet extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rate_type", nullable = false, length = 10, updatable = false)
    private StatutoryRateType rateType;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "employee_rate", precision = 9, scale = 4)
    private BigDecimal employeeRate;

    @Column(name = "employer_rate", precision = 9, scale = 4)
    private BigDecimal employerRate;

    @Column(name = "basis", nullable = false, length = 16, updatable = false)
    private String basis;

    @Column(name = "ceiling_amount", precision = 19, scale = 4)
    private BigDecimal ceilingAmount;

    @Column(name = "headcount_threshold")
    private Short headcountThreshold;

    @Column(name = "active", nullable = false)
    @Setter
    private boolean active = true;

    @Column(name = "description", length = 160)
    @Setter
    private String description;

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

    protected StatutoryRateSet() {}

    public StatutoryRateSet(Long companyId, StatutoryRateType rateType, LocalDate effectiveFrom,
                             BigDecimal employeeRate, BigDecimal employerRate, String basis,
                             BigDecimal ceilingAmount, Short headcountThreshold,
                             String description, Long createdBy) {
        this.companyId          = companyId;
        this.rateType           = rateType;
        this.effectiveFrom      = effectiveFrom;
        this.employeeRate       = employeeRate;
        this.employerRate       = employerRate;
        this.basis              = basis;
        this.ceilingAmount      = ceilingAmount;
        this.headcountThreshold = headcountThreshold;
        this.description        = description;
        this.createdBy          = createdBy;
    }
}
