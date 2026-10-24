package com.erp.modules.sales.domain.enums;

/**
 * State of one item on a restaurant order (ADR-0065 D-5). A PENDING item has not been sent to a
 * prep station and may still be changed or deleted; a SENT item is immutable and can only be
 * VOIDED, which prints a VOID slip at its station.
 */
public enum RestaurantOrderLineStatus {
    PENDING,
    SENT,
    VOIDED
}
