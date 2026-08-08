package com.erp.modules.sales.domain.enums;

/**
 * Verdict of the "did this sale actually happen?" lookup by idempotency key (K11).
 *
 * <p>Until this existed the only way to ask the question was to re-POST the whole sale, which
 * re-ran every business guard against data that may have drifted since — so the answer to "did it
 * post?" could be "the session is closed", which is not an answer at all.
 */
public enum PosSaleLookupVerdict {

    /**
     * A finalised invoice exists for this key. Definitive: the marker and the invoice are written in
     * one transaction, so a stamped marker can only exist if the sale committed.
     */
    POSTED,

    /**
     * No marker exists for this key, so no sale using it has ever committed.
     *
     * <p>The one window this does not cover is an attempt that is committing at this exact instant
     * (its marker row is not yet visible). That is why the client must re-ring a NEVER_POSTED sale
     * <strong>reusing the same {@code Idempotency-Key}</strong>: if the first attempt does commit
     * a moment later, the re-ring finds the marker and returns that same invoice instead of
     * creating a second one.
     */
    NEVER_POSTED,

    /**
     * A marker exists but carries no invoice — an attempt reserved the key and neither completed nor
     * rolled back cleanly. Genuinely unknown; wait a moment and ask again.
     */
    UNKNOWN
}
