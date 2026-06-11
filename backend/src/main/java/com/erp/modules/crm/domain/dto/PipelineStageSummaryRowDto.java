package com.erp.modules.crm.domain.dto;

import java.math.BigDecimal;

/** One row in the pipeline summary (ADR-0031 D-9). */
public record PipelineStageSummaryRowDto(
        String stageUid,
        String stageName,
        short displayOrder,
        long openCount,
        BigDecimal totalValueAmount,
        BigDecimal weightedValueAmount,
        String currency
) {}
