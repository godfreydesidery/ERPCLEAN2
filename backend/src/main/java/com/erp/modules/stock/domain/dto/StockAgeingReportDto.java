package com.erp.modules.stock.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * Stock Ageing — on-hand quantity and value per product split into age buckets, assuming
 * first-in, first-out, plus days since each product last sold.
 *
 * @param buckets              the bucket labels, in column order ("0–30" … "Over 180")
 * @param totalBucketQty       per bucket, across every row
 * @param totalBucketValue     per bucket, across the rows that have a cost
 * @param totalValue           sum of the known row values
 * @param unvaluedProducts     products with stock but no cost — excluded from the value totals
 * @param uncoveredProducts    products with stock older than their recorded movement history
 * @param negativeStockProducts products whose on-hand is below zero at the as-of date — they have
 *                             no age and are left out of the rows, but counted so they are not lost
 * @param neverSoldProducts    products in the rows with no sale on record
 * @param valuedAtCurrentCost  true when the as-of date is in the past: quantities are as of that
 *                             date, but the cost applied is today's average (cost history is not
 *                             kept per day)
 */
public record StockAgeingReportDto(
        ReportCompanyHeaderDto company,
        String asOf,
        String branchName,
        String currency,
        List<String> buckets,
        List<StockAgeingRowDto> rows,
        BigDecimal totalOnHand,
        List<BigDecimal> totalBucketQty,
        List<BigDecimal> totalBucketValue,
        BigDecimal totalValue,
        int unvaluedProducts,
        int uncoveredProducts,
        int negativeStockProducts,
        int neverSoldProducts,
        boolean valuedAtCurrentCost,
        String generatedAt) {
}
