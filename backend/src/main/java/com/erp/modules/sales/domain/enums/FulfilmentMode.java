package com.erp.modules.sales.domain.enums;

/**
 * How a sales order line will be fulfilled (ADR-0029 D-3).
 */
public enum FulfilmentMode {
    /** Issue from own inventory on delivery (default). */
    OWN_STOCK,
    /** Raise a PO to a supplier who ships directly to the customer. */
    DROP_SHIP
}
