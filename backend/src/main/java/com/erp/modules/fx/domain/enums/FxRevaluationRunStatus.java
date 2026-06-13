package com.erp.modules.fx.domain.enums;

/**
 * Lifecycle of an FX revaluation run (ADR-0036 D-6).
 *
 * <ul>
 *   <li>{@code PREVIEWED} — dry run computed but not yet posted to GL.</li>
 *   <li>{@code POSTED}    — GL journal committed; reversal scheduled for next period.</li>
 *   <li>{@code REVERSED}  — the reversal GL entry has been posted (next period open).</li>
 * </ul>
 */
public enum FxRevaluationRunStatus {
    PREVIEWED,
    POSTED,
    REVERSED
}
