package com.erp.modules.fx.domain.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Request body to post a period-end FX revaluation run (ADR-0036 D-6).
 */
public record PostFxRevaluationRequest(
        @NotNull Long      companyId,
        @NotNull String    fiscalPeriodUid,
        @NotNull LocalDate postingDate,
        /** The date from which to pick the spot rate (defaults to period end date if null). */
        LocalDate          spotRateDate
) {}
