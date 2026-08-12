package com.erp.modules.ap.domain.enums;

/**
 * How much of a supplier bill was actually checked against the purchase order and goods receipt
 * (UAT 2026-08-12).
 *
 * <p>The bill-level roll-up of {@code BillMatchResultDto.LineMatchDto.comparisonPerformed}: a line
 * counts as compared only when BOTH legs of the 3-way control ran — the order line resolved to a
 * price AND the receipt line resolved to a received quantity. That is exactly what the
 * {@code bill_match} row records by leaving {@code po_unit_cost_amount} / {@code gr_received_qty}
 * NULL when a leg did not run.
 *
 * <p><b>Derived at read time, never stored.</b> No AP column stands behind it; it is recomputed
 * from the match rows on every read, so it cannot drift away from the evidence.
 *
 * <p><b>Why "unknown" is not folded into "fine".</b> A bill can carry a payable and be MATCHED
 * without a single match row ever existing — an AP opening balance posts its own journal entry and
 * never touches the match engine, and a bill entered with no purchase-order reference passes the
 * control with nothing to compare. Both are legitimate; neither was checked. They answer
 * {@link #NEVER_MATCHED} and {@link #NO_LINES_COMPARED}, never {@link #ALL_LINES_COMPARED}, because
 * a signal that reports silence as verification is worse than no signal at all.
 *
 * <p><b>This is a visibility signal, not a control.</b> Nothing here decides whether a bill matches,
 * holds, posts or pays — it only lets a period-end review find the bills nobody checked.
 */
public enum BillComparisonState {

    /** Every line was compared against both the order price and the received quantity. */
    ALL_LINES_COMPARED,

    /** Some lines were compared and some were not — the bill is only partly evidenced. */
    SOME_LINES_COMPARED,

    /**
     * The match engine ran but could not compare a single line: no purchase order or goods receipt
     * was linked (a service charge), or the linked documents could not be resolved.
     */
    NO_LINES_COMPARED,

    /**
     * No match result exists for this bill at all. Either it has not been matched yet (a draft), or
     * it reached a posted state without the match engine ever seeing it (an AP opening balance).
     */
    NEVER_MATCHED;

    /** True only when every line carries real evidence of a 3-way comparison. */
    public boolean isFullyCompared() {
        return this == ALL_LINES_COMPARED;
    }

    /** True when a period-end reviewer should look at this bill deliberately. */
    public boolean needsReview() {
        return !isFullyCompared();
    }
}
