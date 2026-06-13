package com.erp.modules.hr.domain.event;

import java.time.LocalDate;

/**
 * Outbox payload for {@code PAYROLL.FINALISED} (ADR-0032 D-10).
 * No monetary amounts — the handler re-reads the run for monetary facts.
 */
public record PayrollFinalisedPayload(
        String runUid,
        Long companyId,
        Long branchId,
        LocalDate payDate,
        String runNumber
) {}
