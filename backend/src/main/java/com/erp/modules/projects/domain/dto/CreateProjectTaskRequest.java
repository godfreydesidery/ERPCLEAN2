package com.erp.modules.projects.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Request DTO for creating a task under a project (FR-PROJ-03). */
public record CreateProjectTaskRequest(
        @NotBlank @Size(max = 30) String taskCode,
        @NotBlank @Size(max = 160) String name,
        BigDecimal plannedHours,
        boolean billable
) {}
