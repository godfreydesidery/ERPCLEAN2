package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.PeriodStatus;
import java.time.LocalDate;

/** Response DTO for a fiscal year. */
public record FiscalYearDto(
        Long id,
        String uid,
        Long companyId,
        String yearCode,
        int startMonth,
        LocalDate startDate,
        LocalDate endDate,
        PeriodStatus status
) {}
