package com.erp.modules.fx.domain.dto;

import com.erp.modules.fx.domain.enums.FxRevaluationRunStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire DTO for fx_revaluation_runs header + lines (ADR-0036 D-6).
 */
public record FxRevaluationRunDto(
        Long                        id,
        String                      uid,
        Long                        companyId,
        String                      runNumber,
        Long                        fiscalPeriodId,
        LocalDate                   postingDate,
        LocalDate                   spotRateDate,
        FxRevaluationRunStatus      status,
        BigDecimal                  totalGainAmount,
        BigDecimal                  totalLossAmount,
        BigDecimal                  netAdjustmentAmount,
        String                      glEntryUid,
        String                      reversalGlEntryUid,
        Instant                     executedAt,
        List<FxRevaluationRunLineDto> lines
) {}
