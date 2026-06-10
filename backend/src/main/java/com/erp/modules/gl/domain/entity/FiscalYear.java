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
 * A company's fiscal year (12-period accounting year, ADR-0013 D-2b/D-4, FR-GL-14).
 * company_id scalar, never updated. start_month configurable (default 1 = January).
 */
@Getter
@Entity
@Table(name = "fiscal_years")
public class FiscalYear extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "year_code", nullable = false, length = 12)
    @Setter
    private String yearCode;

    @Column(name = "start_month", nullable = false)
    @Setter
    private int startMonth = 1;

    @Column(name = "start_date", nullable = false)
    @Setter
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    @Setter
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private PeriodStatus status = PeriodStatus.OPEN;

    // Year-end close fields (ADR-0019 D-7, V16 additive columns)
    @Column(name = "closed_at")
    @Setter
    private Instant closedAt;

    @Column(name = "closed_by")
    @Setter
    private Long closedBy;

    @Column(name = "closing_journal_uid", length = 26)
    @Setter
    private String closingJournalUid;

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

    protected FiscalYear() {
        // JPA
    }

    public FiscalYear(Long companyId, String yearCode, int startMonth,
                      LocalDate startDate, LocalDate endDate, Long createdBy) {
        this.companyId = companyId;
        this.yearCode = yearCode;
        this.startMonth = startMonth;
        this.startDate = startDate;
        this.endDate = endDate;
        this.createdBy = createdBy;
    }
}
