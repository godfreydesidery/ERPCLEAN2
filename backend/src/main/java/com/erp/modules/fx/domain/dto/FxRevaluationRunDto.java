package com.erp.modules.fx.domain.dto;

import com.erp.modules.fx.domain.enums.FxRevaluationRunStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire DTO for fx_revaluation_runs header + lines (ADR-0036 D-6).
 * P2-M1: added executedBy, reversalDate, reversalPeriodId, rateType, scopeSummary.
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
        /** app_users.id who posted this run (P2-M1). */
        Long                        executedBy,
        /** Date of the auto-reversal journal (P2-M1). */
        LocalDate                   reversalDate,
        /** fiscal_periods.id of the reversal period (P2-M1). */
        Long                        reversalPeriodId,
        /** Rate type used (e.g. SPOT, CLOSING) (P2-M1). */
        String                      rateType,
        /** Scope summary string (P2-M1). */
        String                      scopeSummary,
        List<FxRevaluationRunLineDto> lines
) {}
