package com.erp.modules.gl.domain.entity;

import com.erp.modules.gl.domain.enums.PeriodStatus;
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
 * One of the 12 monthly fiscal periods of a fiscal year (ADR-0013 D-2b, BR-GL-03, FR-GL-14).
 * status is the posting gate: CLOSED periods reject all new postings.
 * company_id is denormalised from the year for the tenant predicate (no join needed).
 */
@Getter
@Entity
@Table(name = "fiscal_periods")
public class FiscalPeriod extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "fiscal_year_id", nullable = false, updatable = false)
    private Long fiscalYearId;

    @Column(name = "period_no", nullable = false)
    private int periodNo;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private PeriodStatus status = PeriodStatus.OPEN;

    @Column(name = "closed_at")
    @Setter
    private Instant closedAt;

    @Column(name = "closed_by")
    @Setter
    private Long closedBy;

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

    protected FiscalPeriod() {
        // JPA
    }

    public FiscalPeriod(Long companyId, Long fiscalYearId, int periodNo,
                        LocalDate startDate, LocalDate endDate, Long createdBy) {
        this.companyId = companyId;
        this.fiscalYearId = fiscalYearId;
        this.periodNo = periodNo;
        this.startDate = startDate;
        this.endDate = endDate;
        this.createdBy = createdBy;
    }
}
