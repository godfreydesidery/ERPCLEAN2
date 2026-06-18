package com.erp.modules.fx.domain.enums;

/**
 * Sub-ledger source of an FX revaluation run line (X2 — ADR-0036 D-6).
 *
 * <p>Mirrors the {@code chk_fx_revaluation_run_line_source} DB CHECK
 * ({@code source_type IN ('AR','AP','CASH')}). Provided as a type-safe constant set for callers.
 *
 * <p>NOTE (P3): {@code FxRevaluationRunLine.sourceType} is intentionally kept as a {@code String}
 * field because {@code getSourceType()} is consumed as a raw String by
 * {@code FxRevaluationRunLineDto} (a wire record with a {@code String sourceType}). This enum
 * defines the canonical values without changing the wire contract; map the entity field to
 * {@code @Enumerated(STRING)} only once the DTO is migrated to carry the enum.
 */
public enum FxSourceType {
    AR,
    AP,
    CASH
}
