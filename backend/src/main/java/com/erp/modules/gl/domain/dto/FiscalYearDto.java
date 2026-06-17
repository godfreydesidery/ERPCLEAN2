package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.PeriodStatus;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Response DTO for a fiscal year.
 * Fields closedAt / closedBy / closingJournalUid are null when the year is OPEN
 * (or after a reopen); populated by YearEndCloseServiceImpl on close (ADR-0019 D-1/D-7).
 * Fields reopenedAt / reopenedBy are null until the first reopen (P2-M1).
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
        String closingJournalUid,
        // Reopen audit trail (P2-M1) — null if the year has never been reopened
        Instant reopenedAt,
        Long reopenedBy
) {}
