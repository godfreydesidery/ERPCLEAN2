package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.FiscalPeriodDto;
import com.erp.modules.gl.domain.dto.FiscalYearDto;
import com.erp.modules.gl.domain.dto.OpenFiscalYearRequest;
import java.util.List;

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
}
