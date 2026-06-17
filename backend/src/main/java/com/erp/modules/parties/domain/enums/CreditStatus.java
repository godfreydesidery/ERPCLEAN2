package com.erp.modules.parties.domain.enums;

/**
 * Credit-control status for a customer account (ADR-0040 D-5).
 *
 * <ul>
 *   <li>{@link #OK} — no restriction; orders and invoices process normally.</li>
 *   <li>{@link #WARNING} — advisory only; operator is notified but order is NOT blocked.</li>
 *   <li>{@link #ON_HOLD} — hard block; sales orders cannot be confirmed without
 *       {@code SALES.CREDIT.OVERRIDE} permission.</li>
 *   <li>{@link #STOPPED} — hard block (stronger than ON_HOLD — e.g. legal action);
 *       treated identically to ON_HOLD at the SO-confirm gate.</li>
 * </ul>
 */
public enum CreditStatus {
    OK,
    WARNING,
    ON_HOLD,
    STOPPED
}
