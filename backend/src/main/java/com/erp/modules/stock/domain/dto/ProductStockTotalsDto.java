package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * Grand totals for the Stock Value report.
 *
 * <p>{@code unvaluedItems} and {@code unpricedItems} are carried beside the money so the totals
 * cannot quietly mislead. A product with no cost contributes nothing to {@code totalCostValue}, and
 * one with no selling price contributes nothing to {@code totalSaleValue} — without these counts a
 * half-priced catalogue produces a confident-looking total that is simply too low, which is exactly
 * how bulk-imported stock went unnoticed.
 */
public record ProductStockTotalsDto(
        BigDecimal totalQuantity,
        BigDecimal totalCostValue,
        BigDecimal totalSaleValue,
        /** totalSaleValue - totalCostValue: the margin still sitting on the shelf. */
        BigDecimal potentialMargin,
        /** Rows holding stock but carrying no cost — excluded from totalCostValue. */
        int unvaluedItems,
        /** Rows holding stock but carrying no selling price — excluded from totalSaleValue. */
        int unpricedItems,
        /**
         * Rows INCLUDED in the totals that are no longer in the catalogue but still hold stock. They
         * belong in a valuation — the GL is carrying them — but a reader comparing this report
         * against the Product List would otherwise find items here that are not there.
         */
        int discontinuedItems) {
}
