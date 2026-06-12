package com.erp.modules.budgeting.domain.dto;

import com.erp.modules.gl.domain.enums.AccountType;

/**
 * Input parameters for the budget-vs-actual variance report (ADR-0034 D-8, FR-BUD-15).
 *
 * <p>{@code costCentreValueUid} is the ADR-0025 {@code dimension_values.uid} for the COST_CENTRE
 * slot; null = company-wide variance (sum across all centres + Unallocated bucket).
 *
 * <p>Period range (1..12, the fiscal year's month ordinals). Default 1..12 = full year.
 */
public record VarianceQuery(
        Long companyId,
        String fiscalYearUid,
        int fromPeriodNo,
        int toPeriodNo,
        /** ADR-0025 dimension_values.uid for COST_CENTRE slot; null = company-wide. */
        String costCentreValueUid,
        /** Optional account type filter; null = all types. */
        AccountType accountType
) {
    public VarianceQuery {
        if (fromPeriodNo < 1 || fromPeriodNo > 12) throw new IllegalArgumentException("fromPeriodNo must be 1..12");
        if (toPeriodNo   < fromPeriodNo || toPeriodNo > 12) throw new IllegalArgumentException("toPeriodNo must be >= fromPeriodNo and <= 12");
    }
}
