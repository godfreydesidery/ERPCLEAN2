package com.erp.modules.projects.domain.entity;

import com.erp.platform.common.domain.MasterStatus;
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

/**
 * Informational timesheet line (ADR-0033 D-2, FR-PROJ-12).
 * Posts NO GL in v1 — provides a planned-rate labour estimate only (OQ-PROJ-05).
 */
@Getter
@Entity
@Table(name = "project_timesheets")
public class ProjectTimesheet extends UidEntity {

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "project_task_id")
    @Setter
    private Long projectTaskId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** FK → app_users.id; the person who worked. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "work_date", nullable = false)
    @Setter
    private LocalDate workDate;

    @Column(name = "hours", nullable = false, precision = 9, scale = 2)
    @Setter
    private BigDecimal hours;

    @Column(name = "overtime_hours", precision = 9, scale = 2)
    @Setter
    private BigDecimal overtimeHours;

    @Column(name = "is_billable", nullable = false)
    @Setter
    private boolean billable = true;

    /** Informational planned-rate for the cost estimate — not posted to GL. */
    @Column(name = "planned_rate_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal plannedRateAmount;

    @Column(name = "notes", length = 255)
    @Setter
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

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

    protected ProjectTimesheet() {
        // JPA
    }

    public ProjectTimesheet(Long projectId, Long companyId, Long branchId, Long userId,
                            LocalDate workDate, BigDecimal hours, Long createdBy) {
        this.projectId  = projectId;
        this.companyId  = companyId;
        this.branchId   = branchId;
        this.userId     = userId;
        this.workDate   = workDate;
        this.hours      = hours;
        this.createdBy  = createdBy;
    }
}
