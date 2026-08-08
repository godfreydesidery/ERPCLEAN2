package com.erp.modules.stock.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.stock.domain.enums.StockMovementReportMode;
import java.util.List;

/**
 * Period Stock Movement report (K9) — the report the client asked for when they said the existing
 * "Stock Report" was not one: that screen is a present-moment snapshot with no date range at all.
 *
 * <p>Exactly one of {@code summaryRows} / {@code detailRows} is populated, per {@code mode}; the
 * other is empty. {@code totals} always covers the whole matching set, never just the current page.
 *
 * <p>Paging fields are plain {@code int}s (like {@code PageMeta}) so they serialise as JSON numbers
 * — the global Long-as-string rule protects 64-bit ids, and none of these are ids.
 *
 * @param company       letterhead block for the export
 * @param fromDate      inclusive period start, ISO date
 * @param toDate        inclusive period end, ISO date
 * @param mode          SUMMARY or DETAIL
 * @param branchName    the branch filter's display name; {@code null} when unfiltered (all branches)
 * @param productLabel  the product filter's display name; {@code null} when unfiltered
 * @param currency      company base currency (for a future value column; quantities are unit-based)
 * @param summaryRows   populated in SUMMARY mode
 * @param detailRows    populated in DETAIL mode
 * @param totals        period totals across all matching products
 * @param page          zero-based page index
 * @param size          page size
 * @param totalElements total rows across all pages (products in SUMMARY, movements in DETAIL)
 * @param totalPages    ceil(totalElements / size)
 * @param generatedAt   ISO-8601 instant the report was produced
 */
public record StockMovementReportDto(
        ReportCompanyHeaderDto               company,
        String                               fromDate,
        String                               toDate,
        StockMovementReportMode              mode,
        String                               branchName,
        String                               productLabel,
        String                               currency,
        List<StockMovementSummaryRowDto>     summaryRows,
        List<StockMovementDetailRowDto>      detailRows,
        StockMovementReportTotalsDto         totals,
        int                                  page,
        int                                  size,
        int                                  totalElements,
        int                                  totalPages,
        String                               generatedAt) {
}
