package com.erp.modules.projects.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Request DTO for recording timesheet hours against a project task (FR-PROJ-12). */
public record CreateTimesheetRequest(
        String projectTaskUid,
        @NotNull Long userId,
        @NotNull LocalDate workDate,
        @NotNull @DecimalMin("0.01") BigDecimal hours,
        boolean billable,
        BigDecimal plannedRateAmount,
        String notes
) {}
