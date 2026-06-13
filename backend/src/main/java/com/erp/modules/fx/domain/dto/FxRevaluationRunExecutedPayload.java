package com.erp.modules.fx.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Outbox payload for the FX_REVALUATION_EXECUTED domain event (ADR-0036 D-6/D-10).
 * Shaped after {@code DepreciationRunExecutedPayload}.
 */
public record FxRevaluationRunExecutedPayload(
        String     runUid,
        Long       companyId,
        Long       fiscalPeriodId,
        LocalDate  postingDate,
        LocalDate  spotRateDate,
        BigDecimal totalGainAmount,
        BigDecimal totalLossAmount,
        BigDecimal netAdjustmentAmount,
        String     glEntryUid,
        Instant    executedAt
) {}
