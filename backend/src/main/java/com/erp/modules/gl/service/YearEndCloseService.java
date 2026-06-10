package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.FiscalYearDto;

/**
 * Year-End Close operations (ADR-0019 D-1).
 *
 * <p>Two operations, each in one atomic TX:
 * <ul>
 *   <li>{@link #closeFiscalYear} — guards → compute P&L balances → post the closing entry
 *       (while period is still OPEN, D-5) → auto-close the year's periods → mark CLOSED.
 *   <li>{@link #reopenFiscalYear} — guards → reopen periods FIRST (so the reversal can post,
 *       D-6) → postReversal → mark OPEN, clear close stamps.
 * </ul>
 */
public interface YearEndCloseService {

    /**
     * Closes an OPEN fiscal year: posts one balanced closing journal (YEAR_END_CLOSE, dated
     * end_date) zeroing every P&L account and rolling the net to 3900 Retained Earnings, then
     * auto-closes the year's periods and marks the year CLOSED.
     *
     * @param fiscalYearUid the uid of the fiscal year to close
     * @return the updated FiscalYearDto (status = CLOSED, closedAt/closedBy/closingJournalUid set)
     * @throws com.erp.platform.common.api.NotFoundException   if the year does not exist
     * @throws com.erp.platform.common.api.ConflictException   if guards fail (already closed,
     *                                                          no periods, prior year not closed)
     * @throws IllegalStateException                            if RETAINED_EARNINGS config missing
     */
    FiscalYearDto closeFiscalYear(String fiscalYearUid);

    /**
     * Reopens a CLOSED fiscal year (must be the most-recently-closed): reopens the periods,
     * posts the reversal of the closing journal (restoring P&L + backing out 3900), marks OPEN.
     *
     * @param fiscalYearUid the uid of the fiscal year to reopen
     * @return the updated FiscalYearDto (status = OPEN, close stamps cleared)
     * @throws com.erp.platform.common.api.NotFoundException   if the year does not exist
     * @throws com.erp.platform.common.api.ConflictException   if guards fail (not closed,
     *                                                          not the most-recently-closed)
     */
    FiscalYearDto reopenFiscalYear(String fiscalYearUid);
}
