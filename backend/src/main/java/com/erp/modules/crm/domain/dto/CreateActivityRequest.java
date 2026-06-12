package com.erp.modules.crm.domain.dto;

import com.erp.modules.crm.domain.enums.ActivityType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;

public record CreateActivityRequest(
        @NotNull Long companyId,
        @NotNull ActivityType activityType,
        /** Exactly one of leadUid or opportunityUid must be set. */
        String leadUid,
        String opportunityUid,
        @NotBlank String subject,
        String body,
        Instant occurredAt,
        /** TASK only. */
        LocalDate dueDate,
        /** TASK only. */
        Long assigneeUserId
) {}
