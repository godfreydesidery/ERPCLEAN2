package com.erp.modules.sales.domain.enums;

/**
 * Lifecycle of a {@code fiscal_receipts} row (ADR-0049 §4).
 *
 * <ul>
 *   <li>{@link #PENDING} — reserved for the async/queued follow-up path; in today's synchronous
 *       slice a row is created PENDING and resolved within the same request/transaction.
 *   <li>{@link #ISSUED} — a real fiscal number + verification data were obtained. Terminal;
 *       {@code fiscal_number}/{@code verification_url}/{@code signature}/{@code device_serial}/
 *       {@code issued_at}/{@code provider_code} are populated. Never re-fiscalised.
 *   <li>{@link #FAILED} — a provider attempted and errored (offline/rejected/timeout). Retryable.
 *   <li>{@link #NOT_CONFIGURED} — the honest default ran; nothing was fabricated. Retryable.
 *   <li>{@link #VOID} — reserved: set if the invoice is voided or a fiscal void is issued. No
 *       device round-trip happens in this slice.
 * </ul>
 */
public enum FiscalReceiptStatus {
    PENDING,
    ISSUED,
    FAILED,
    NOT_CONFIGURED,
    VOID
}
