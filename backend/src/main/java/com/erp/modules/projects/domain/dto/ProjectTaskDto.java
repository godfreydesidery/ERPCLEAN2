package com.erp.modules.projects.domain.dto;

import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;

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
        boolean billable,
        MasterStatus status
) {}
