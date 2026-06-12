package com.erp.modules.stock.domain.enums;

/**
 * Physical type of a stock location (ADR-0028 D-4).
 * Stored as VARCHAR(20); CHECK constraint in {@code chk_stock_location_type}.
 */
public enum LocationType {
    WAREHOUSE,
    STORE,
    VAN,
    QUARANTINE,
    OTHER
}
