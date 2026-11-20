package com.erp.modules.subscription.domain.enums;

/**
 * Where a tenant stands on the non-payment ladder (ADR-0069 D-6). Computed from the dates, never
 * stored.
 */
public enum SubscriptionStage {
    /** No subscription row: behaves exactly as before plans existed (D-10). */
    UNRESTRICTED,
    /** On trial, on or before the trial end date. */
    TRIAL,
    /** Paid up, or no due date at all. */
    ACTIVE,
    /** Past due: full access, the banner warns. */
    GRACE,
    /** Past grace: every write refused, POS included. */
    READ_ONLY,
    /** Past read-only: sign-in and every request refused. */
    SUSPENDED;

    /** True while writes are refused. */
    public boolean blocksWrites() {
        return this == READ_ONLY || this == SUSPENDED;
    }
}
