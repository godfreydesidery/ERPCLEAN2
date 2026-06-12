package com.erp.modules.stock.domain.enums;

/**
 * Lifecycle status for a stock count document (ADR-0028 D-6).
 *
 * <p>Valid transitions: DRAFT → COUNTING → POSTED; DRAFT/COUNTING → CANCELLED.
 * POSTED is immutable — corrections require a new count (FR-INVD-15).
 */
public enum StockCountStatus {
    DRAFT,
    COUNTING,
    POSTED,
    CANCELLED
}
