package com.erp.modules.products.domain.enums;

/**
 * The effect a promotion applies to a line price (ADR-0029 D-6, FR-SD-11).
 * PERCENT_DISCOUNT = reduce the base price by a %; AMOUNT_DISCOUNT = reduce by a fixed amount;
 * OVERRIDE_PRICE   = replace the unit price entirely.
 */
public enum PromotionEffect {
    PERCENT_DISCOUNT,
    AMOUNT_DISCOUNT,
    OVERRIDE_PRICE
}
