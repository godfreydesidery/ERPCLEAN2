package com.erp.modules.bi.domain.dto;

import java.util.List;

/**
 * 12-period revenue and net-profit trend (ADR-0037 D-4, D-6).
 * Two parallel lists — same period axis, different metric labels.
 */
public record TrendDto(
        String              metricLabel,
        String              currency,
        List<TrendPointDto> points
) {}
