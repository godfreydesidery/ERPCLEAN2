package com.erp.modules.products.domain.enums;

/**
 * The applicability scope of a price list (P2 D5, ADR-0041 D5).
 *
 * <p>Recorded on the row (not a configurable GlConfig tiebreak — ADR-0041 D5 FORK). v1 is
 * advisory metadata for pricing resolution; richer per-scope binding is a later refinement.
 *
 * <ul>
 *   <li>{@link #GLOBAL} — applies company-wide (default).</li>
 *   <li>{@link #CUSTOMER} — intended for specific customers (via customer.default_price_list_id).</li>
 *   <li>{@link #BRANCH} — intended for a branch.</li>
 *   <li>{@link #SEGMENT} — intended for a customer segment.</li>
 * </ul>
 */
public enum PriceListScope {
    GLOBAL,
    CUSTOMER,
    BRANCH,
    SEGMENT
}
