package com.erp.modules.crm.domain.dto;

import com.erp.modules.crm.domain.entity.Activity;
import com.erp.modules.crm.domain.enums.ActivityType;
import com.erp.platform.common.domain.MasterStatus;
import java.time.Instant;
import java.time.LocalDate;

public record ActivityDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String activityNumber,
        ActivityType activityType,
        Long leadId,
        Long opportunityId,
        String subject,
        String body,
        Instant occurredAt,
        LocalDate dueDate,
        Long assigneeUserId,
        String outcome,
        Instant reminderAt,
        Integer durationMinutes,
        boolean done,
        Instant doneAt,
        MasterStatus status,
        Long version,
        Instant createdAt,
        Long createdBy,
        Instant updatedAt,
        Long updatedBy
) {
    public static ActivityDto from(Activity a) {
        return new ActivityDto(
                a.getId(), a.getUid(), a.getCompanyId(), a.getBranchId(),
                a.getActivityNumber(), a.getActivityType(), a.getLeadId(), a.getOpportunityId(),
                a.getSubject(), a.getBody(), a.getOccurredAt(), a.getDueDate(),
                a.getAssigneeUserId(), a.getOutcome(), a.getReminderAt(), a.getDurationMinutes(),
                a.isDone(), a.getDoneAt(), a.getStatus(),
                a.getVersion(), a.getCreatedAt(), a.getCreatedBy(),
                a.getUpdatedAt(), a.getUpdatedBy());
    }
}
