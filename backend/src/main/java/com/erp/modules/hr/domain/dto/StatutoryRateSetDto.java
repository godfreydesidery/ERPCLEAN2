package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.StatutoryRateType;
import java.math.BigDecimal;
import java.time.LocalDate;

public record StatutoryRateSetDto(
        Long id,
        String uid,
        Long companyId,
        StatutoryRateType rateType,
        LocalDate effectiveFrom,
        BigDecimal employeeRate,
        BigDecimal employerRate,
        String basis,
        BigDecimal ceilingAmount,
        Short headcountThreshold,
        boolean active,
        String description
) {}
