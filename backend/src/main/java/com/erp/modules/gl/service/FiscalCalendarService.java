package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.FiscalPeriodDto;
import com.erp.modules.gl.domain.dto.FiscalYearDto;
import com.erp.modules.gl.domain.dto.OpenFiscalYearRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FiscalCalendarService {

    /** Creates a new fiscal year + 12 periods for a company (GL.PERIOD.CLOSE permission). */
    FiscalYearDto openFiscalYear(OpenFiscalYearRequest req);

    FiscalYearDto getFiscalYearByUid(String uid);

    List<FiscalYearDto> listFiscalYears(Long companyId);

    List<FiscalPeriodDto> listPeriods(Long companyId);

    List<FiscalPeriodDto> listPeriodsForYear(String fiscalYearUid);

    FiscalPeriodDto getPeriodByUid(String uid);

    /** Closes an open period (GL.PERIOD.CLOSE). */
    FiscalPeriodDto closePeriod(String periodUid);

    /** Reopens a closed period (GL.PERIOD.CLOSE). */
    FiscalPeriodDto reopenPeriod(String periodUid);

    /** Seeds the current fiscal year + 12 periods for a new company. Idempotent. */
    void seedCurrentYear(Long companyId);

    /**
     * Fiscal-year rollover (ACC-01). Idempotent provisioning, run at start-up and daily by
     * {@link FiscalYearRolloverJob}: makes sure a fiscal year covers {@code today} and that the year
     * after it exists. Each new year starts the day after the company's latest year ends, with the
     * same start month and twelve monthly periods; its code follows the existing convention
     * ("FY2026" → "FY2027"). Never creates a year that overlaps another; a date in a gap or before
     * the first year is left to the accountant. System operation — no caller scope check.
     *
     * @return the years it opened (empty when nothing was needed)
     */
    List<FiscalYearDto> ensureCurrentAndNextYear(Long companyId, LocalDate today);

    /**
     * Opens the year that follows {@code fiscalYearUid} if no later year exists yet (ACC-16: a
     * year-end close leaves the next year ready). Same rules as {@link #ensureCurrentAndNextYear}.
     * The caller has already scope-checked the year.
     */
    Optional<FiscalYearDto> ensureFollowingYear(String fiscalYearUid);
}
