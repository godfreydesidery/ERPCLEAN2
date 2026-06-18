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
 * Project task — one level in v1 (ADR-0033 D-2, FR-PROJ-03).
 * parent_id is reserved for a future hierarchy; always NULL in v1.
 */
@Getter
@Entity
@Table(name = "project_tasks")
public class ProjectTask extends UidEntity {

    @Column(name = "project_id", nullable = false, updatable = false)
    private Long projectId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "task_code", nullable = false, length = 30)
    @Setter
    private String taskCode;

    @Column(name = "name", nullable = false, length = 160)
    @Setter
    private String name;

    /** Reserved for future hierarchy; always NULL in v1. */
    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "planned_hours", precision = 9, scale = 2)
    @Setter
    private BigDecimal plannedHours;

    @Column(name = "planned_start_date")
    @Setter
    private LocalDate plannedStartDate;

    @Column(name = "planned_end_date")
    @Setter
    private LocalDate plannedEndDate;

    @Column(name = "actual_hours", precision = 9, scale = 2)
    @Setter
    private BigDecimal actualHours;

    /** P2: nullable soft-FK to app_users — the assignee. */
    @Column(name = "assignee_user_id")
    @Setter
    private Long assigneeUserId;

    @Column(name = "is_billable", nullable = false)
    @Setter
    private boolean billable = true;

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

    protected ProjectTask() {
        // JPA
    }

    public ProjectTask(Long projectId, Long companyId, Long branchId,
                       String taskCode, String name, Long createdBy) {
        this.projectId  = projectId;
        this.companyId  = companyId;
        this.branchId   = branchId;
        this.taskCode   = taskCode;
        this.name       = name;
        this.createdBy  = createdBy;
    }
}
