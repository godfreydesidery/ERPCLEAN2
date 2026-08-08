package com.erp.modules.stock.domain.enums;

/**
 * Presentation mode for the period Stock Movement report (K9).
 *
 * <p>SUMMARY is one row per product for the whole period (opening / purchases / sales /
 * adjustments-other / closing). DETAIL is one row per {@code stock_movements} entry with a running
 * balance. Both are driven by the same period window and the same filters.
 */
public enum StockMovementReportMode {

    /** One aggregated row per product for the period. */
    SUMMARY,

    /** One row per movement, with a per-product running balance. */
    DETAIL
}
