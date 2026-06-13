package com.erp.modules.bi.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One point on the 12-period revenue / net-profit trend (ADR-0037 D-4, D-6).
 * Server-side placement only — Java never sums raw lines (NFR-REP-02).
 */
public record TrendPointDto(
        String     periodLabel,
        LocalDate  periodStart,
        LocalDate  periodEnd,
        BigDecimal value
) {}
