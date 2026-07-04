package com.erp.platform.fiscal;

/**
 * The three shapes a {@link FiscalisationProvider} attempt can resolve to (ADR-0049 §4).
 *
 * <ul>
 *   <li>{@link #ISSUED} — a real fiscal number + verification data were obtained. Terminal;
 *       never re-attempted.
 *   <li>{@link #NOT_CONFIGURED} — no device/adapter is configured; nothing was fabricated.
 *       Retryable (a later adapter wiring can make a retry succeed).
 *   <li>{@link #FAILED} — an adapter attempted and errored (offline/rejected/timeout). Retryable.
 * </ul>
 */
public enum FiscalisationOutcome {
    ISSUED,
    NOT_CONFIGURED,
    FAILED
}
