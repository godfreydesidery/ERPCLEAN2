package com.erp.modules.ar.domain.dto;

import com.erp.modules.ar.domain.enums.AgeingBucket;
import java.math.BigDecimal;

/** One ageing bucket row for a customer (ADR-0014 D-7). */
public record ArAgeingRowDto(
        AgeingBucket bucket,
        BigDecimal amount,
        String currency
) {}
