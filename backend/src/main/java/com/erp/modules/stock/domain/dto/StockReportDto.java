package com.erp.modules.stock.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * Printable current-stock listing with selling price (SAM Electronix go-live).
 *
 * <p><b>The branch fields answer "which one am I looking at?"</b> (UAT, 2026-08). A branch manager
 * can pass {@code branchUid} and get a 200 back; before these fields nothing in the body said
 * whether the filter had been honoured, on numbers that get signed off every morning. Now:
 * <ul>
 *   <li>{@code branchUid} / {@code branchName} — the branch the listing was narrowed to, read back
 *       off the branch row itself. Both {@code null} when the listing spans the whole company.</li>
 *   <li>{@code branchLabel} — <b>never null</b>. The branch's name when one was filtered, the
 *       literal {@code "All branches"} when none was. Every surface (screen, PDF/Excel/CSV export,
 *       any integration) prints this one server-authored phrase, so "no branch filter" is stated
 *       out loud instead of being inferred from an absent field.</li>
 * </ul>
 */
public record StockReportDto(
        ReportCompanyHeaderDto   company,
        String                   branchUid,
        String                   branchName,
        String                   branchLabel,
        String                   currency,
        List<StockReportRowDto>  rows,
        BigDecimal               totalValue,
        String                   generatedAt) {
}
