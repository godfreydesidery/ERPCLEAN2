package com.erp.modules.projects.domain.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
        // PROJECTS-LOW: DB column is VARCHAR(255); cap at 255 here so validation fires
        // before the DB truncation/check-violation (aligns entity @Column(length=255)).
        @Size(max = 255, message = "notes must not exceed 255 characters") String notes
) {}
