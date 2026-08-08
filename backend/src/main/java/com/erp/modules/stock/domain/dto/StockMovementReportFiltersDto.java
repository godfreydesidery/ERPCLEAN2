package com.erp.modules.stock.domain.dto;

import com.erp.modules.stock.domain.enums.StockMovementReportMode;
import java.time.LocalDate;

/**
 * The five query knobs of the period Stock Movement report (K9), carried as one value so the
 * read and export paths cannot drift apart and neither signature grows past readability.
 *
 * @param fromDate   inclusive period start
 * @param toDate     inclusive period end
 * @param mode       SUMMARY (per product) or DETAIL (per movement); {@code null} means SUMMARY
 * @param branchUid  optional branch narrowing; {@code null}/blank means every branch in the company
 * @param productUid optional product narrowing; {@code null}/blank means every product
 */
public record StockMovementReportFiltersDto(
        LocalDate               fromDate,
        LocalDate               toDate,
        StockMovementReportMode mode,
        String                  branchUid,
        String                  productUid) {
}
