package com.erp.modules.budgeting.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Request to upsert (replace) lines on a DRAFT budget version (ADR-0034 D-4c, FR-BUD-07/08).
 *
 * <p>Three entry modes (FR-BUD-08):
 * <ul>
 *   <li>{@code DIRECT} — per-period amounts provided directly in {@code lines}.</li>
 *   <li>{@code ANNUAL_SPREAD} — a single annual amount, spread evenly across 12 periods
 *       (HALF_UP, remainder on last period). {@code annualAmount} required.</li>
 *   <li>{@code SEED} — copy lines from another version. {@code seedFromVersionUid} required.
 *       (Handled at the version create step; may also be re-seeded here.)</li>
 * </ul>
 */
public record UpsertBudgetLineRequest(
        @NotNull EntryMode mode,
        /** For DIRECT mode: the per-period lines to upsert. Required when mode=DIRECT. */
        @Valid List<LineInputDto> lines,
        /** For ANNUAL_SPREAD mode: annual amount spread across all 12 periods. */
        BigDecimal annualAmount,
        /** For ANNUAL_SPREAD / SEED: the accountUid to budget for. */
        String accountUid,
        /** For SEED mode: version uid to copy lines from. */
        String seedFromVersionUid
) {

    public enum EntryMode { DIRECT, ANNUAL_SPREAD, SEED }

    public record LineInputDto(
            @NotNull String accountUid,
            @NotNull String fiscalPeriodUid,
            @NotNull BigDecimal amount,
            @Size(max = 255) String lineMemo
    ) {}
}
