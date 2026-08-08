package com.erp.modules.sales.domain.enums;

/**
 * Machine-readable outcome of a rejected POS sale attempt (K11).
 *
 * <p>Before this existed, "the session is closed", "not enough stock", "below cost" and a genuine
 * "your earlier sale is still being processed" were <em>all</em> HTTP 409 with nothing but prose to
 * tell them apart. A till client is (correctly) forbidden from branching on error text, so it
 * treated every 409 as "still in flight", re-armed its unfinished-sale dialog and looped forever.
 *
 * <p>Each constant maps to a distinct HTTP status at the controller, so a client that reads only the
 * status code already behaves correctly; the same value is also echoed in the
 * {@code X-Pos-Sale-Status} response header and in the response body's {@code data.code}.
 *
 * <ul>
 *   <li>{@link #IN_FLIGHT} — 409. An earlier attempt with the same {@code Idempotency-Key} has not
 *       finished. This is the ONLY case in which a client should keep a pending sale armed and
 *       retry / poll the lookup endpoint.</li>
 *   <li>{@link #REJECTED} — 422. A business rule refused the sale (session not open, insufficient
 *       stock, below-cost policy, tenders short, age gate…). Nothing is in flight and nothing was
 *       written: the client must NOT arm a pending sale, it must show the message.</li>
 *   <li>{@link #STALE_REPLAY} — 410. The client is trying to complete a sale that is too old to be
 *       re-priced and re-stocked into the current period. The pending sale must be discarded and
 *       rung again from scratch.</li>
 * </ul>
 */
public enum PosSaleFlowStatus {

    /** An earlier attempt with the same idempotency key has not completed yet. Retry / poll. */
    IN_FLIGHT,

    /** A business rule refused this sale. Nothing is pending — do not re-arm. */
    REJECTED,

    /** The attempt is too old to replay. Discard the pending sale and ring it again. */
    STALE_REPLAY
}
