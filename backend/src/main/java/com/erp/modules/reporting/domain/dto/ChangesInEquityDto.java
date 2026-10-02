package com.erp.modules.reporting.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Statement of Changes in Equity over [fromDate, toDate] — the fourth primary statement
 * (reporting.md §2 deferred item; built on the BR-REP-05 equity fold).
 *
 * <p>Company-wide. Opening = the Balance Sheet equity as-at {@code fromDate − 1}; closing = the
 * Balance Sheet equity as-at {@code toDate}, component by component.
 *
 * @param profitForPeriod  the Income Statement's net profit for the same period
 * @param reconciliation   opening + movements (summed over every component) == the Balance Sheet's
 *                         total equity at the period end (current column); the comparative column
 *                         carries the opening check (Σ opening == Balance Sheet equity the day before)
 * @param transfersCheck   the transfers column nets to zero (a transfer moves equity, never creates it)
 */
public record ChangesInEquityDto(
        StatementHeaderDto         header,
        ReportCompanyHeaderDto     company,
        List<EquityMovementRowDto> rows,
        EquityMovementRowDto       totals,
        BigDecimal                 balanceSheetOpeningEquity,
        BigDecimal                 balanceSheetClosingEquity,
        BigDecimal                 profitForPeriod,
        ReconciliationDto          reconciliation,
        ReconciliationDto          transfersCheck
) {}
