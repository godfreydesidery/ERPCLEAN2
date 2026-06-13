package com.erp.modules.fx.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Result of a revaluation preview — dry run, no GL posting (ADR-0036 D-6).
 */
public record FxRevaluationPreviewDto(
        Long                            companyId,
        String                          fiscalPeriodUid,
        LocalDate                       spotRateDate,
        BigDecimal                      totalGainAmount,
        BigDecimal                      totalLossAmount,
        BigDecimal                      netAdjustmentAmount,
        List<FxRevaluationPreviewLineDto> lines
) {}
