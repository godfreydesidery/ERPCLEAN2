package com.erp.modules.fx.domain.dto;

import java.math.BigDecimal;

/**
 * One line in a revaluation preview (dry run) — no GL posting (ADR-0036 D-6).
 */
public record FxRevaluationPreviewLineDto(
        String     sourceType,
        String     currency,
        Long       controlAccountId,
        BigDecimal outstandingTxnAmount,
        BigDecimal carryingBaseAmount,
        BigDecimal spotRate,
        BigDecimal revaluedBaseAmount,
        BigDecimal adjustmentAmount
) {}
