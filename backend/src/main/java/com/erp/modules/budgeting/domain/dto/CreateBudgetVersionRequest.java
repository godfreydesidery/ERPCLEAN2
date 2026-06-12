package com.erp.modules.budgeting.domain.dto;

import jakarta.validation.constraints.Size;

/**
 * Request to create a new DRAFT version of an existing budget (ADR-0034 D-5, FR-BUD-13).
 *
 * <p>If {@code seedFromVersionUid} is provided, lines are copied from that version
 * (FR-BUD-08c). The new version opens as DRAFT regardless.
 */
public record CreateBudgetVersionRequest(
        @Size(max = 120) String label,
        /** UID of an existing version to copy lines from (FR-BUD-08c). Null = blank version. */
        String seedFromVersionUid
) {}
