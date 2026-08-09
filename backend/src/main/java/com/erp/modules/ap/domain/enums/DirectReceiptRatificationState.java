package com.erp.modules.ap.domain.enums;

/**
 * Where the purchase order backing a supplier bill sits on the post-hoc ratification path
 * (K3 follow-up, Kilimanjaro 2026-08-08).
 *
 * <p>Derived at read time from the backing order, never persisted — there is no AP column behind it.
 * A direct goods receipt (goods delivered with no prior LPO) is exempt from PO pre-approval and is
 * instead ratified afterwards by a manager; this is how AP sees that decision, so a clerk knows
 * before payment time that cash is held.
 *
 * @see com.erp.modules.ap.service.DirectReceiptRatificationGuard
 */
public enum DirectReceiptRatificationState {

    /** No backing order, or an ordinary buyer-raised (MANUAL) order — the gate does not apply. */
    NOT_APPLICABLE,

    /** Direct receipt whose ratification is still sitting in a manager's approvals inbox. */
    AWAITING_RATIFICATION,

    /** Direct receipt whose ratification a manager refused. */
    RATIFICATION_REFUSED,

    /** Direct receipt a manager has ratified — pays like any other bill. */
    RATIFIED;

    /** True when cash must not leave against a bill in this state. */
    public boolean blocksPayment() {
        return this == AWAITING_RATIFICATION || this == RATIFICATION_REFUSED;
    }
}
