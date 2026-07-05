package com.erp.modules.products.domain.enums;

/**
 * How a rule-based mass price change adjusts each existing price on a list.
 * {@code PERCENT} adds a percentage of the current price (e.g. 5 = +5%, -10 = −10%);
 * {@code AMOUNT} adds a fixed amount (may be negative); {@code SET} replaces with a fixed amount.
 */
public enum MassPriceChangeType {
    PERCENT,
    AMOUNT,
    SET
}
