package com.erp.modules.purchases.domain.enums;

/**
 * Where a suggested purchase-order line unit cost came from (SAM client feedback 2026-08).
 *
 * <p>Ordered exactly as the fallback chain is tried — the first source that yields a price wins.
 * Serialised as its plain name on the wire; the web maps it to friendly provenance text
 * ("From last quote" / "From last purchase" / "From product cost"), so the codes themselves are
 * never shown to a user.
 */
public enum PurchaseCostSource {

    /** Last price this supplier quoted for this product/unit (supplier_quote_lines, BR-PROC-09). */
    LAST_QUOTE,

    /** Last price actually ordered from this supplier for this product/unit (purchase_order_lines). */
    LAST_PURCHASE,

    /** The product master's cost (buying) price — per base unit only. */
    PRODUCT_COST
}
