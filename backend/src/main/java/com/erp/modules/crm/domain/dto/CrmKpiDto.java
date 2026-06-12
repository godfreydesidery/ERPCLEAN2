package com.erp.modules.crm.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Basic CRM KPIs for a period (ADR-0031 D-9). */
public record CrmKpiDto(
        LocalDate periodStart,
        LocalDate periodEnd,
        long wonCount,
        long lostCount,
        BigDecimal winRatePercent,
        BigDecimal avgCycleDays
) {}
