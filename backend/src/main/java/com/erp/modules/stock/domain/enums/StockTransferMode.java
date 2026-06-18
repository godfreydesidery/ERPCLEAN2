package com.erp.modules.stock.domain.enums;

/**
 * Settlement mode of a stock transfer (ADR-0028 D-5).
 *
 * <ul>
 *   <li>INSTANT — single net-zero move, DRAFT → COMPLETED.</li>
 *   <li>IN_TRANSIT — two-legged, DRAFT → DISPATCHED → RECEIVED.</li>
 * </ul>
 */
public enum StockTransferMode {
    INSTANT,
    IN_TRANSIT
}
