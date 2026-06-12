package com.erp.modules.budgeting.domain.dto;

import jakarta.validation.constraints.Size;

/** Request body for approving a SUBMITTED budget version (ADR-0034 D-5, FR-BUD-12). */
public record ApproveBudgetVersionRequest(
        @Size(max = 500) String note
) {}
