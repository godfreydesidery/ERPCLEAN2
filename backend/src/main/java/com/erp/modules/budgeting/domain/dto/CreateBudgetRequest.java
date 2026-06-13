package com.erp.modules.budgeting.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to create a new budget header + initial DRAFT version (ADR-0034 D-4a, FR-BUD-06).
 *
 * <p>{@code costCentreValueUid} is the ADR-0025 {@code dimension_values.uid} for the COST_CENTRE
 * slot. Null = company-wide budget (BR-BUD-03). Resolved via
 * {@link com.erp.modules.costing.service.DimensionService#resolveValue}.
 */
public record CreateBudgetRequest(
        @NotNull Long companyId,
        @NotBlank @Size(max = 160) String name,
        @NotNull String fiscalYearUid,
        /** ADR-0025 dimension_values.uid for the COST_CENTRE slot; null = company-wide. */
        String costCentreValueUid,
        @Size(max = 500) String notes,
        /** Optional label for the initial version 1 (e.g. "Initial budget"). */
        @Size(max = 120) String initialVersionLabel
) {}
