package com.erp.modules.stock.domain.enums;

/**
 * Per-unit status for a serial-tracked item (ADR-0028 D-7, FR-INVD-23..27).
 * Transitions: IN_STOCK → ISSUED (on sale/delivery) → RETURNED (→ IN_STOCK on restock).
 */
public enum SerialStatus {
    IN_STOCK,
    ISSUED,
    RETURNED
}
