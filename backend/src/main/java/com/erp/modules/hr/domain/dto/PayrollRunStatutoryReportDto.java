package com.erp.modules.hr.domain.dto;

import java.util.List;

/**
 * Statutory summary of ONE payroll run (FR-HR-23): run totals plus the per-employee breakdown.
 *
 * @param provisional true while the run is not yet APPROVED (DRAFT / CALCULATED) — the figures can
 *                    still change on recalculation and must not be filed; also true for a REVERSED
 *                    run, whose liabilities were cancelled
 */
public record PayrollRunStatutoryReportDto(
        Long companyId,
        StatutorySummaryDto summary,
        List<StatutoryLineDto> lines,
        boolean provisional,
        String currency,
        String generatedAt
) {}
