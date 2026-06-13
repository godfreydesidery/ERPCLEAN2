package com.erp.modules.hr.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PayeBandSetDto(
        Long id,
        String uid,
        Long companyId,
        LocalDate effectiveFrom,
        BigDecimal taxFreeThreshold,
        String description,
        List<PayeBandDto> bands
) {
    public record PayeBandDto(
            Long id,
            String uid,
            short bandNo,
            BigDecimal lowerBound,
            BigDecimal marginalRate,
            BigDecimal cumulativeFixedTax
    ) {}
}
