package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.PeriodStatus;
import java.time.Instant;
import java.time.LocalDate;

/** Response DTO for a fiscal period. */
public record FiscalPeriodDto(
        Long id,
        String uid,
        Long companyId,
        Long fiscalYearId,
        int periodNo,
        LocalDate startDate,
        LocalDate endDate,
        PeriodStatus status,
        Instant closedAt
) {}
