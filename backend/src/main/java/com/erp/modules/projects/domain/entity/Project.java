package com.erp.modules.projects.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.modules.projects.domain.enums.ProjectStatus;
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
 * Project master (ADR-0033 D-2, FR-PROJ-01). Extends UidEntity for id + uid + @Version.
 * company_id / branch_id are scalar Longs — no cross-module @ManyToOne.
 * customer_id / manager_user_id are nullable scalar FKs (service-enforced same-company).
 */
@Getter
@Entity
@Table(name = "projects")
public class Project extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "project_number", nullable = false, length = 30, updatable = false)
    private String projectNumber;

    @Column(name = "name", nullable = false, length = 160)
    @Setter
    private String name;

    /** Nullable FK → customers.id; NULL = internal project (no customer). */
    @Column(name = "customer_id")
    @Setter
    private Long customerId;

    /** Nullable FK → app_users.id; the project manager. */
    @Column(name = "manager_user_id")
    @Setter
    private Long managerUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "project_status", nullable = false, length = 20)
    @Setter
    private ProjectStatus projectStatus = ProjectStatus.DRAFT;

    @Column(name = "planned_start_date")
    @Setter
    private LocalDate plannedStartDate;

    @Column(name = "planned_end_date")
    @Setter
    private LocalDate plannedEndDate;

    @Column(name = "actual_start_date")
    @Setter
    private LocalDate actualStartDate;

    @Column(name = "actual_end_date")
    @Setter
    private LocalDate actualEndDate;

    @Column(name = "budget_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal budgetAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

    @Column(name = "activated_at")
    @Setter
    private Instant activatedAt;

    @Column(name = "completed_at")
    @Setter
    private Instant completedAt;

    @Column(name = "cancelled_at")
    @Setter
    private Instant cancelledAt;

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

    protected Project() {
        // JPA
    }

    public Project(Long companyId, Long branchId, String projectNumber, String name,
                   String currency, Long createdBy) {
        this.companyId     = companyId;
        this.branchId      = branchId;
        this.projectNumber = projectNumber;
        this.name          = name;
        this.currency      = CurrencyCode.ofNullable(currency);
        this.createdBy     = createdBy;
    }
}
