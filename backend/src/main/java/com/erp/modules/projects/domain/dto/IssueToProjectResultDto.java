package com.erp.modules.projects.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Result DTO for the issue-materials-to-project path (ADR-0033 D-5).
 */
public record IssueToProjectResultDto(
        String issueUid,
        String issueNumber,
        String projectUid,
        List<IssueLineResultDto> lines,
        String cogsGlEntryUid,
        BigDecimal totalValue,
        String currency
) {
    /** Result for one issued line. */
    public record IssueLineResultDto(
            String productUid,
            BigDecimal qty,
            BigDecimal value
    ) {}
}
