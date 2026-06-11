package com.erp.modules.crm.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Forecast for a period (ADR-0031 D-9). */
public record ForecastDto(
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal weightedValueAmount,
        long openCount,
        String currency
) {}
