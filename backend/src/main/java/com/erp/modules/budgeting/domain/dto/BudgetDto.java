package com.erp.modules.budgeting.domain.dto;

import java.time.Instant;
import java.util.List;

/**
 * Budget header response DTO (ADR-0034 D-1, API surface).
 * Includes id + uid per the identity pattern (PROJECT-CONVENTIONS §identity).
 * Long ids serialise as JSON strings via the global Jackson config — no per-field annotation needed.
 */
public record BudgetDto(
        Long id,
        String uid,
        Long companyId,
        String budgetNumber,
        String name,
        Long fiscalYearId,
        String fiscalYearUid,
        String fiscalYearCode,
        Long costCentreValueId,
        String costCentreValueUid,
        String costCentreValueName,
        String notes,
        Long version,
        Instant createdAt,
        Long createdBy,
        Instant updatedAt,
        Long updatedBy,
        /** Included when fetching budget detail — all versions with their status. */
        List<BudgetVersionDto> versions
) {}
