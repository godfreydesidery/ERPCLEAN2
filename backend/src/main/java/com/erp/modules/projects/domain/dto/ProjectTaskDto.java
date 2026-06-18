package com.erp.modules.projects.domain.dto;

import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Project task response DTO (ADR-0033 D-2, FR-PROJ-03). */
public record ProjectTaskDto(
        Long id,
        String uid,
        Long projectId,
        Long companyId,
        Long branchId,
        String taskCode,
        String name,
        Long parentId,
        BigDecimal plannedHours,
        LocalDate plannedStartDate,
        LocalDate plannedEndDate,
        BigDecimal actualHours,
        Long assigneeUserId,
        boolean billable,
        MasterStatus status
) {}
