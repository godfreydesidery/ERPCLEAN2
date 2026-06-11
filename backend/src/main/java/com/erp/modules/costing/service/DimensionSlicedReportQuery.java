package com.erp.modules.costing.service;

import com.erp.modules.costing.domain.dto.DimensionActualsRowDto;
import com.erp.modules.costing.domain.dto.DimensionSlicedTbDto;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import java.time.LocalDate;
import java.util.List;

/**
 * Dimension-sliced trial balance and actuals reads (ADR-0025 D-8, FR-CC-14/15/16/17).
 * Scoped + {@code assertCanActIn} on every entry point. RBAC: COSTING.VIEW + GL.VIEW.
 *
 * <p>{@link #actualsByAccountValuePeriod} is the published Budgeting/Projects contract (FR-CC-17):
 * those modules call this instead of querying {@code journal_lines} directly (D-8).
 */
public interface DimensionSlicedReportQuery {

    /**
     * The dimension-sliced trial balance (FR-CC-14).
     *
     * @param companyId the tenant
     * @param slot      which slot to slice by
     * @param valueUid  filter to a specific value (null = all values for the slot)
     * @param rollUp    when true, expand a parent value to include descendant lines (FR-CC-16)
     * @param periodId  optional fiscal period filter (null = all time)
     */
    DimensionSlicedTbDto slicedTrialBalance(Long companyId, DimensionSlot slot,
                                             String valueUid, boolean rollUp, Long periodId);

    /**
     * Actuals grouped by (account, value, period) — the Budgeting/Projects read contract (FR-CC-17).
     * Period attribution is derived from journal_entries.fiscal_period_id.
     *
     * @param companyId the tenant
     * @param slot      which slot to read actuals for
     * @param fromDate  start of the date window (inclusive)
     * @param toDate    end of the date window (inclusive)
     * @return rows sorted by accountId, valueId, periodNo
     */
    List<DimensionActualsRowDto> actualsByAccountValuePeriod(Long companyId, DimensionSlot slot,
                                                              LocalDate fromDate, LocalDate toDate);
}
