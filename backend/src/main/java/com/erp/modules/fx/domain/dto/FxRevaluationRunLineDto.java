package com.erp.modules.fx.domain.dto;

import java.math.BigDecimal;

/**
 * Wire DTO for a single fx_revaluation_run_lines row (ADR-0036 D-6).
 */
public record FxRevaluationRunLineDto(
        Long   id,
        String uid,
        String sourceType,
        String currency,
        Long   controlAccountId,
        BigDecimal outstandingTxnAmount,
        BigDecimal carryingBaseAmount,
        BigDecimal spotRate,
        BigDecimal revaluedBaseAmount,
        BigDecimal adjustmentAmount
) {}
