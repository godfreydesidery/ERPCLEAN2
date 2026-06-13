package com.erp.modules.projects.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request DTO for creating a project (ADR-0033 D-1, FR-PROJ-01).
 * companyUid + branchUid identify the tenant. customerUid / managerUid are optional.
 */
public record CreateProjectRequest(
        @NotBlank String companyUid,
        @NotBlank String branchUid,
        @NotBlank @Size(max = 160) String name,
        String customerUid,
        String managerUid,
        LocalDate plannedStartDate,
        LocalDate plannedEndDate,
        BigDecimal budgetAmount,
        @Size(max = 500) String notes
) {}
