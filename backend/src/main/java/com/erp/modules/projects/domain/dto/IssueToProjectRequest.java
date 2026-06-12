package com.erp.modules.projects.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Request DTO for the issue-materials-to-project path (ADR-0033 D-5, FR-PROJ-08).
 * Issues stock to a project, posting COGS at moving average tagged to the project.
 */
public record IssueToProjectRequest(
        @NotBlank String companyUid,
        @NotBlank String branchUid,
        @NotBlank String projectUid,
        String projectTaskUid,
        @NotEmpty List<IssueLine> lines,
        LocalDate issueDate,
        String reason
) {
    /** One product line in an issue-to-project request. */
    public record IssueLine(
            @NotBlank String productUid,
            @NotNull BigDecimal qty
    ) {}
}
