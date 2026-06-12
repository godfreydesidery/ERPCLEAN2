package com.erp.modules.products.domain.enums;

/**
 * Which pricing rule resolved the unit price for a line (ADR-0029 D-7).
 * Stored on the line as a diagnostic — never used in financial math.
 */
public enum PriceSource {
    CUSTOMER_PRICE,
    PROMOTION,
    TIER,
    LIST_PRICE,
    NONE
}
