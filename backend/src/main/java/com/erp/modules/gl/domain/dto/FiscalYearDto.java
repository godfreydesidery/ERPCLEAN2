package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.PeriodStatus;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Response DTO for a fiscal year.
 * Fields closedAt / closedBy / closingJournalUid are null when the year is OPEN
 * (or after a reopen); populated by YearEndCloseServiceImpl on close (ADR-0019 D-1/D-7).
 */
public record FiscalYearDto(
        Long id,
        String uid,
        Long companyId,
        String yearCode,
        int startMonth,
        LocalDate startDate,
        LocalDate endDate,
        PeriodStatus status,
        // Year-end close fields (ADR-0019 D-7) — null when OPEN or after reopen
        Instant closedAt,
        Long closedBy,
        String closingJournalUid
) {}
