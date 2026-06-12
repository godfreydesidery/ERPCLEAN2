package com.erp.modules.budgeting.domain.dto;

import com.erp.modules.gl.domain.enums.AccountType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Budget-vs-actual variance report output (ADR-0034 D-8, FR-BUD-15/16).
 *
 * <p>{@code noApprovedBudget = true} when no APPROVED version exists for the chosen scope —
 * all budget amounts are 0 (FR-BUD-16, BR-BUD-12). Report is never silently wrong.
 *
 * <p>Variance = actual − budget (signed; favourable/adverse label is a presentation concern in
 * the UI, not stored here — BR-BUD-15). The Unallocated bucket (cost_centre_value_id IS NULL
 * actuals) is shown as a separate entry in rows when a centre is chosen (OQ-BUD-07).
 */
public record VarianceReportDto(
        HeaderDto header,
        List<VarianceRowDto> rows,
        Map<AccountType, BigDecimal> totalsBudgetByType,
        Map<AccountType, BigDecimal> totalsActualByType,
        Map<AccountType, BigDecimal> totalsVarianceByType
) {

    public record HeaderDto(
            Long companyId,
            String fiscalYearUid,
            String fiscalYearCode,
            int fromPeriodNo,
            int toPeriodNo,
            String costCentreValueUid,
            String costCentreValueName,
            boolean noApprovedBudget
    ) {}
}
