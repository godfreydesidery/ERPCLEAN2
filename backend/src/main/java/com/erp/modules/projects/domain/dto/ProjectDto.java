package com.erp.modules.projects.domain.dto;

import com.erp.modules.projects.domain.enums.ProjectStatus;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Project response DTO (ADR-0033 D-1). Includes both id and uid (PROJECT-CONVENTIONS §3.4).
 * Long ids serialise as JSON strings (JacksonConfig global setting — no per-field annotation).
 */
public record ProjectDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String projectNumber,
        String name,
        Long customerId,
        Long managerUserId,
        ProjectStatus projectStatus,
        LocalDate plannedStartDate,
        LocalDate plannedEndDate,
        LocalDate actualStartDate,
        LocalDate actualEndDate,
        BigDecimal budgetAmount,
        String currency,
        String notes,
        MasterStatus status,
        Instant activatedAt,
        Instant completedAt,
        Instant cancelledAt
) {}
