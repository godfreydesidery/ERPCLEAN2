package com.erp.modules.ap.domain.enums;

/**
 * Per-line 3-way match result (ADR-0015 D-2c).
 *
 * <p>These four values are pinned by the DB CHECK {@code chk_bill_match_status} (V12), and the
 * schema is frozen — adding a constant here without a migration makes every insert of it fail at
 * runtime. So a line whose order/receipt could not be resolved (the control could not run) reuses
 * the HELD_* family rather than getting a status of its own: HELD_PRICE_VARIANCE when the price
 * could not be verified or is out of tolerance, HELD_QTY_VARIANCE when the receipt is what is
 * missing. The reason and the next step ride on {@code BillMatchResultDto.LineMatchDto.matchNote}
 * (and {@code bill_match.variance_reason}), not on the status name.
 */
public enum BillMatchStatus {
    MATCHED,
    HELD_PRICE_VARIANCE,
    HELD_QTY_VARIANCE,
    VARIANCE_ACCEPTED
}
