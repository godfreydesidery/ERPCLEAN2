package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;

/**
 * One product row of the SUMMARY Stock Movement report (K9).
 *
 * <p>The five quantity columns close arithmetically by construction:
 * {@code closingQty = openingQty + purchasesIn − salesOut + adjustmentsOther}.
 * {@code adjustmentsOther} is NOT decoration — transfers, stock counts, imports, project issues and
 * production movements all change stock without being a purchase or a sale, so without that column
 * the other four would not reconcile and the report would read as broken.
 *
 * <p>All quantities are in the product's BASE unit; {@code unitLabel} names it (a carton-based base
 * unit otherwise makes every number ambiguous).
 *
 * @param productCode      product code ({@code null} only if the product row vanished)
 * @param productName      product description
 * @param unitLabel        base-unit symbol, falling back to the unit code
 * @param openingQty       signed on-hand derived from all movements strictly before the period
 * @param purchasesIn      net purchased quantity in the period (receipts − receipt reversals − returns)
 * @param salesOut         net sold quantity in the period, expressed POSITIVE (issues − sale reversals)
 * @param adjustmentsOther net of every other movement type in the period (signed)
 * @param closingQty       signed on-hand at the end of the period
 */
public record StockMovementSummaryRowDto(
        String     productCode,
        String     productName,
        String     unitLabel,
        BigDecimal openingQty,
        BigDecimal purchasesIn,
        BigDecimal salesOut,
        BigDecimal adjustmentsOther,
        BigDecimal closingQty) {
}
