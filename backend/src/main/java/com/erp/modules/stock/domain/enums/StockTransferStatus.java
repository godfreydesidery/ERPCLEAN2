package com.erp.modules.stock.domain.enums;

/**
 * Lifecycle status for a stock transfer document (ADR-0028 D-5).
 *
 * <p>Valid transitions:
 * <ul>
 *   <li>IN_TRANSIT mode: DRAFT → DISPATCHED → RECEIVED</li>
 *   <li>INSTANT mode:    DRAFT → COMPLETED</li>
 *   <li>Both modes:      DRAFT → CANCELLED</li>
 * </ul>
 */
public enum StockTransferStatus {
    DRAFT,
    DISPATCHED,
    RECEIVED,
    COMPLETED,
    CANCELLED
}
