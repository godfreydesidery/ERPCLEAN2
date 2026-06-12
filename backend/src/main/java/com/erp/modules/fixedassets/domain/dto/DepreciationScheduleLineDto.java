package com.erp.modules.fixedassets.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DepreciationScheduleLineDto(
        Long id,
        String uid,
        Long fixedAssetId,
        int periodSeq,
        int scheduleVersion,
        LocalDate periodDate,
        BigDecimal plannedCharge,
        BigDecimal accumulatedAfter,
        BigDecimal nbvAfter,
        boolean posted,
        Long depreciationRunId
) {}
