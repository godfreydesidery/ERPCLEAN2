package com.erp.modules.budgeting.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request body for rejecting a SUBMITTED budget version (ADR-0034 D-5, FR-BUD-12, BR-BUD-13). */
public record RejectBudgetVersionRequest(
        @NotBlank @Size(max = 500) String reason
) {}
