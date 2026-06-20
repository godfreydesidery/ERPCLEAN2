package com.erp.modules.products.domain.enums;

/**
 * Age-restriction classification of a product (ADR-0044 D-3a, BR-11).
 * Stored as VARCHAR(20) in {@code products.restricted_kind}, mirroring {@link VatStatus}.
 *
 * <p>Default is {@code NONE} — all existing products are unaffected (the V71 migration backfills
 * every row to NONE, so no POS sale is gated until a cashier manager explicitly sets AGE_18 or
 * AGE_21 on the product).
 */
public enum RestrictedKind {

    /** No age restriction — the product may be sold to any customer without acknowledgement. */
    NONE,

    /** Restricted to buyers aged 18+. A sale requires {@code ageVerified=true} or override. */
    AGE_18,

    /** Restricted to buyers aged 21+ (e.g. spirits in certain jurisdictions). */
    AGE_21;

    /**
     * True when this product carries an age restriction that must be acknowledged at the till.
     */
    public boolean isRestricted() {
        return this != NONE;
    }
}
