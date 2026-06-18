package com.erp.modules.fx.domain.dto;

import java.math.BigDecimal;

/**
 * Wire DTO for a single fx_revaluation_run_lines row (ADR-0036 D-6).
 * P2-M1: added priorRate (original conversion rate) and journalLineId (GL drill-down).
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
        BigDecimal adjustmentAmount,
        /** Original conversion rate that produced the carrying base (P2-M1). */
        BigDecimal priorRate,
        /** journal_lines.id of the GL leg posted for this line (P2-M1). */
        Long   journalLineId
) {}
