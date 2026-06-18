package com.erp.modules.parties.domain.enums;

/**
 * Coarse commercial segment for a customer (P2 D5, ADR-0041 D5 Tier-2 simplification).
 *
 * <p>A simple enum stand-in for the deferred rich CustomerSegment/Group master. Used for
 * reporting and price-list scoping ({@link com.erp.modules.products.domain.enums.PriceListScope#SEGMENT}).
 * Defaults to {@link #OTHER}.
 */
public enum CustomerSegment {
    RETAIL,
    WHOLESALE,
    GOVERNMENT,
    OTHER
}
