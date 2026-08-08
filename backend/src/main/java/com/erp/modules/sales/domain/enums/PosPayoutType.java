package com.erp.modules.sales.domain.enums;

/**
 * Type of a POS session payout (ADR-0029 D-2).
 * REFUND   = cash paid out on a POS refund (cash returns to customer).
 * PAID_OUT = misc cash payout — drawer-to-safe drop / petty payout.
 * EXPENSE  = a categorised business expense paid out of the drawer (transport, water, casual
 *            labour…). Carries a {@code category} and the expense account it was posted to (K8, V94).
 * All three SUBTRACT from expected cash (payouts are outflows from the till).
 *
 * <p>Order is display order: the X/Z-read payout breakdown is zero-filled in enum order, so a
 * printed report keeps the same rows in the same places shift to shift. EXPENSE is appended last
 * so the two pre-existing lines stay where cashiers already expect them.
 *
 * <p>Every value here must also be admitted by {@code chk_pos_session_payout_type} — widened to
 * {@code ('REFUND','PAID_OUT','EXPENSE')} by V94. Adding a value without widening the CHECK makes
 * every insert of that type fail at the database.
 */
public enum PosPayoutType {
    REFUND,
    PAID_OUT,
    EXPENSE
}
