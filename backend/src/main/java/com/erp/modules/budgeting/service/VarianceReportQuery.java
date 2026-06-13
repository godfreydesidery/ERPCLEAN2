package com.erp.modules.budgeting.service;

import com.erp.modules.budgeting.domain.dto.DepartmentalActualsDto;
import com.erp.modules.budgeting.domain.dto.VarianceQuery;
import com.erp.modules.budgeting.domain.dto.VarianceReportDto;

/**
 * Budget-vs-actual variance read (ADR-0034 D-8, FR-BUD-15/16/17/18).
 *
 * <p>Consumes ADR-0025's {@code DimensionSlicedReportQuery.actualsByAccountValuePeriod} for the
 * GL actuals — budgeting does NOT query journal_lines directly for cost-centre actuals (D-8 contract).
 * assertCanActIn on every entry point (NFR-BUD-01).
 */
public interface VarianceReportQuery {

    /**
     * Run the budget-vs-actual variance report (FR-BUD-15/16).
     * When no approved budget exists for the scope, all budget amounts are 0 + noApprovedBudget=true
     * (FR-BUD-16, BR-BUD-12).
     */
    VarianceReportDto run(VarianceQuery query);

    /**
     * Departmental actuals: GL actuals grouped by cost_centre_value × account for a period range
     * (FR-BUD-17). No budget join. NULL cost_centre_value_id = Unallocated (OQ-BUD-07).
     */
    DepartmentalActualsDto departmentalActuals(Long companyId, String fiscalYearUid,
                                               int fromPeriodNo, int toPeriodNo);
}
