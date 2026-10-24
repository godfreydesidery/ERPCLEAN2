package com.erp.modules.sales.domain.enums;

/**
 * Lifecycle of a restaurant order, the "check" (ADR-0065 D-5).
 *
 * <pre>
 * OPEN ──bill──▶ BILLED ──pay──▶ PAID          (terminal)
 *   ▲              │
 *   └──add/void────┘   (changing a billed order reopens it)
 * OPEN/BILLED ──cancel──▶ CANCELLED           (terminal)
 * </pre>
 */
public enum RestaurantOrderStatus {
    OPEN,
    BILLED,
    PAID,
    CANCELLED;

    /** True while the order is still on the floor (can be changed, billed or paid). */
    public boolean isLive() {
        return this == OPEN || this == BILLED;
    }
}
