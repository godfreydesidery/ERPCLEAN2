package com.erp.modules.hr.domain.event;

import java.time.LocalDate;

/**
 * Outbox payload for {@code PAYROLL.REVERSED} (ADR-0032 D-10).
 */
public record PayrollReversedPayload(
        String runUid,
        Long companyId,
        Long branchId,
        LocalDate payDate,
        String runNumber,
        String originalGlEntryUid
) {}
