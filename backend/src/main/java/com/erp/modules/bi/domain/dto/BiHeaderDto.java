package com.erp.modules.bi.domain.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Dashboard header — mirrors {@code StatementHeaderDto} but for the BI composite (ADR-0037 D-6).
 * currency resolved via CompanyRepository.getBaseCurrency() — NEVER hardcoded.
 *
 * <p><b>The branch fields answer "which one am I looking at?"</b> (UAT, 2026-08). The dashboard
 * accepts a {@code branchId} but the header used to carry only company identity, so a filtered view
 * and a group-wide one were indistinguishable in the payload:
 * <ul>
 *   <li>{@code branchUid} / {@code branchName} — the branch the request was filtered to, read back
 *       off the branch row. Both {@code null} when no branch filter was applied.</li>
 *   <li>{@code branchLabel} — <b>never null</b>. The branch's name when one was filtered, the
 *       literal {@code "All branches"} when none was, and {@code "Unknown branch"} when a branch id
 *       was supplied that does not belong to this company. That last case must never read as
 *       "All branches": the filter was not honoured, and saying so is the point.</li>
 * </ul>
 * Note the header describes the REQUEST's scope. Only the CRM and sales-by-branch panels are
 * branch-dimensional; the finance, working-capital, inventory and trend panels are group-wide by
 * construction and label themselves as such on screen.
 */
public record BiHeaderDto(
        Long      companyId,
        String    companyName,
        String    branchUid,
        String    branchName,
        String    branchLabel,
        String    currency,
        String    periodLabel,
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate asOf,
        Instant   generatedAt
) {}
