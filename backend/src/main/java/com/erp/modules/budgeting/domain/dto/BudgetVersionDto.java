package com.erp.modules.budgeting.domain.dto;

import com.erp.modules.budgeting.domain.enums.BudgetVersionStatus;
import java.time.Instant;
import java.util.List;

/**
 * Budget version response DTO (ADR-0034 D-4b).
 * Includes lines when fetching version detail.
 */
public record BudgetVersionDto(
        Long id,
        String uid,
        Long budgetId,
        String budgetUid,
        Long companyId,
        Long fiscalYearId,
        Long costCentreValueId,
        int versionNo,
        BudgetVersionStatus status,
        String label,
        Long seededFromVersionId,
        String seededFromVersionUid,
        Instant submittedAt,
        Long submittedBy,
        Instant approvedAt,
        Long approvedBy,
        Instant rejectedAt,
        Long rejectedBy,
        Instant supersededAt,
        String decisionReason,
        Long version,
        Instant createdAt,
        Long createdBy,
        /** Included when fetching version detail. */
        List<BudgetLineDto> lines
) {}
