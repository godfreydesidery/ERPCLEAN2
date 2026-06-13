package com.erp.modules.bi.domain.dto;

import java.math.BigDecimal;

/**
 * Inventory panel: stock value stat-card + GL-1300 recon tie (ADR-0037 D-6 O-1).
 * Sourced from StockValuationQuery.report — shows the stat-card only, NOT the full per-product table.
 */
public record InventorySummaryDto(
        BigDecimal stockValue,
        boolean    stockTies,
        BigDecimal stockDifference
) {}
