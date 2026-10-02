package com.erp.modules.hr.domain.dto;

import java.util.List;

/**
 * Payroll Statutory report over a pay-date range (FR-HR-23): one row per counted run + totals.
 *
 * <p>Only APPROVED, POSTED and PAID runs are counted — their figures are final. Runs still in DRAFT
 * or CALCULATED (figures can change) and REVERSED runs (liability cancelled) are left out, and
 * counted in {@code pendingRunCount} / {@code reversedRunCount} so the reader knows they exist.
 */
public record PayrollStatutoryPeriodReportDto(
        String fromDate,
        String toDate,
        List<StatutorySummaryDto> runs,
        StatutoryTotalsDto totals,
        int pendingRunCount,
        int reversedRunCount,
        String currency,
        String generatedAt
) {}
