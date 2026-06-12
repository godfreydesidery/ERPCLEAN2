package com.erp.modules.projects.domain.dto;

import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Project timesheet response DTO (ADR-0033 D-2, FR-PROJ-12). */
public record ProjectTimesheetDto(
        Long id,
        String uid,
        Long projectId,
        Long projectTaskId,
        Long companyId,
        Long branchId,
        Long userId,
        LocalDate workDate,
        BigDecimal hours,
        boolean billable,
        BigDecimal plannedRateAmount,
        String notes,
        MasterStatus status
) {}
