package com.erp.modules.bi.domain.dto;

import java.math.BigDecimal;

/**
 * Inventory panel: stock value stat-card + GL-1300 recon tie (ADR-0037 D-6 O-1).
 * Sourced from StockValuationQuery.report — shows the stat-card only, NOT the full per-product table.
 *
 * <p>{@code stockSaleValue} is the same stock at the default selling price (the Stock Value
 * report's {@code totalSaleValue}); null when it could not be computed or nothing is priced.
 * {@code unpricedItems} counts lines holding stock with no selling price — they are left out of
 * {@code stockSaleValue}, so the figure is never read as complete when it is not.
 */
public record InventorySummaryDto(
        BigDecimal stockValue,
        boolean    stockTies,
        BigDecimal stockDifference,
        BigDecimal stockSaleValue,
        int        unpricedItems
) {}
