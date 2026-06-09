package com.erp.modules.ap.domain.dto;

import com.erp.modules.ar.domain.enums.AgeingBucket;
import java.math.BigDecimal;

/** One row of the AP ageing breakdown (ADR-0015 D-7, mirrors AR). */
public record ApAgeingRowDto(
        AgeingBucket bucket,
        BigDecimal amount,
        String currency
) {}
