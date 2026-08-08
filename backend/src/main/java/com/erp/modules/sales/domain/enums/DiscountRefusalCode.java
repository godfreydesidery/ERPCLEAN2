package com.erp.modules.sales.domain.enums;

/**
 * Why the discount policy (K7) refused a line, as a token a client may branch on.
 *
 * <p><b>Why this exists.</b> The web checkout used to decide whether to offer the "Ask a supervisor"
 * button by searching the server's refusal sentence for the words "discount" and "approval" (UAT
 * finding #13). One rewording — or one translation — and the button silently disappears, leaving a
 * cashier at a dead end with no way to tell that a remedy existed. The message is for the human; this
 * is for the client.
 *
 * <p>The names travel on the wire verbatim, so they are part of the API contract: rename one and the
 * clients that branch on it stop recognising the refusal, which is the very failure this replaces.
 * Add new values rather than repurposing an existing one.
 */
public enum DiscountRefusalCode {

    /**
     * APPROVE stance, discount above the ceiling, and no (or no usable) manager named. A supervisor
     * step-up can satisfy this, so a client should offer that path.
     */
    DISCOUNT_APPROVAL_REQUIRED,

    /**
     * A manager WAS named but the approval was not usable — unknown or deactivated account, or a
     * user who does not genuinely hold {@code SALES.DISCOUNT.OVERRIDE} in the invoice's company.
     * Still satisfiable, by a different supervisor.
     */
    DISCOUNT_APPROVAL_NOT_ACCEPTED,

    /**
     * BLOCK stance: the discount is above the ceiling and nobody may authorise it. A client must NOT
     * offer a step-up here — prompting for an approval that would be refused anyway is a lie.
     */
    DISCOUNT_ABOVE_CEILING
}
