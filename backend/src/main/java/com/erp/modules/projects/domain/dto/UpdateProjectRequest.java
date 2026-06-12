package com.erp.modules.projects.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Request DTO for editing a project's mutable fields (ADR-0033 D-2). */
public record UpdateProjectRequest(
        @NotBlank @Size(max = 160) String name,
        String customerUid,
        String managerUid,
        LocalDate plannedStartDate,
        LocalDate plannedEndDate,
        BigDecimal budgetAmount,
        @Size(max = 500) String notes
) {}
