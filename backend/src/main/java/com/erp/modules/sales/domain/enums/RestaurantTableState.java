package com.erp.modules.sales.domain.enums;

/**
 * A table's live state on the floor, DERIVED from its live orders and never stored (ADR-0065 D-3):
 * BILLED when any live order on it is billed, OCCUPIED when it has an open one, else FREE.
 */
public enum RestaurantTableState {
    FREE,
    OCCUPIED,
    BILLED
}
