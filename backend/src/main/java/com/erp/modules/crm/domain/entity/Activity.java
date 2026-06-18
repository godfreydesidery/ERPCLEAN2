package com.erp.modules.crm.domain.entity;

import com.erp.modules.crm.domain.enums.ActivityType;
import com.erp.platform.common.domain.MasterStatus;
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
 * CRM activity — a logged interaction against exactly one lead or opportunity (ADR-0031 D-6).
 * TASK type additionally carries due_date + assignee_user_id + done flag.
 * DB CHECK chk_activity_parent enforces exactly one parent.
 */
@Getter
@Entity
@Table(name = "activities")
public class Activity extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "activity_number", nullable = false, length = 30)
    @Setter
    private String activityNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "activity_type", nullable = false, length = 20)
    private ActivityType activityType;

    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "opportunity_id")
    private Long opportunityId;

    @Column(name = "subject", nullable = false, length = 200)
    @Setter
    private String subject;

    @Column(name = "body", length = 2000)
    @Setter
    private String body;

    @Column(name = "occurred_at", nullable = false)
    @Setter
    private Instant occurredAt = Instant.now();

    @Column(name = "due_date")
    @Setter
    private LocalDate dueDate;

    @Column(name = "assignee_user_id")
    @Setter
    private Long assigneeUserId;

    @Column(name = "outcome", length = 255)
    @Setter
    private String outcome;

    @Column(name = "reminder_at")
    @Setter
    private Instant reminderAt;

    @Column(name = "duration_minutes")
    @Setter
    private Integer durationMinutes;

    @Column(name = "done", nullable = false)
    @Setter
    private boolean done = false;

    @Column(name = "done_at")
    @Setter
    private Instant doneAt;

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

    protected Activity() { /* JPA */ }

    /** Activity against a lead. */
    public Activity(Long companyId, Long branchId, ActivityType activityType,
                    Long leadId, String subject, Long createdBy) {
        this.companyId = companyId;
        this.branchId = branchId;
        this.activityType = activityType;
        this.leadId = leadId;
        this.subject = subject;
        this.createdBy = createdBy;
    }

    /** Activity against an opportunity. */
    public Activity(Long companyId, Long branchId, ActivityType activityType,
                    Long leadId, Long opportunityId, String subject, Long createdBy) {
        this.companyId = companyId;
        this.branchId = branchId;
        this.activityType = activityType;
        this.leadId = leadId;
        this.opportunityId = opportunityId;
        this.subject = subject;
        this.createdBy = createdBy;
    }
}
