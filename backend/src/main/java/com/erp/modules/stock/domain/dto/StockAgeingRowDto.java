package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One product's stock on hand split by age.
 *
 * @param bucketQty          quantity in each age bucket, in {@link StockAgeingReportDto#buckets}
 *                           order; always sums to {@code onHand}
 * @param bucketValue        value in each bucket, same order; null when the product has no cost.
 *                           Always sums to {@code value}
 * @param unitCost           current moving-average cost; null when the stock was never costed
 * @param uncoveredQty       on-hand quantity no recorded inbound movement accounts for (stock that
 *                           predates the movement history, typically). It is placed in the OLDEST
 *                           bucket and flagged, because its true age is unknown
 * @param lastSaleDate       the last day the product was sold in scope, up to the as-of date;
 *                           null if never
 * @param daysSinceLastSale  as-of date − last sale date; null if never sold
 */
public record StockAgeingRowDto(
        String productUid,
        String productCode,
        String productName,
        String unitName,
        BigDecimal onHand,
        BigDecimal unitCost,
        BigDecimal value,
        List<BigDecimal> bucketQty,
        List<BigDecimal> bucketValue,
        BigDecimal uncoveredQty,
        String lastSaleDate,
        Long daysSinceLastSale) {
}
