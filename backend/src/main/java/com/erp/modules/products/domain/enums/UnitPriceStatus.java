package com.erp.modules.products.domain.enums;

/**
 * Outcome of resolving one product's price inside a batch price read.
 *
 * <p>A batch never fails as a whole because one row could not be priced — the row carries its
 * status and a null amount instead, so a POS search page can render "no price" on that line and
 * still show prices for the other 59.
 */
public enum UnitPriceStatus {

    /** A price was resolved; {@code amount} and {@code currency} are populated. */
    RESOLVED,

    /** The product has no price row configured at all; {@code amount}/{@code currency} are null. */
    NO_PRICE,

    /**
     * The requested unit is neither the product's base unit nor one of its configured bulk-pack
     * units, so no price can be expressed in it; {@code amount}/{@code currency} are null.
     */
    UNIT_NOT_APPLICABLE
}
