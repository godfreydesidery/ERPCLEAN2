package com.erp.modules.gl.domain.dto;

/** Request to open a new fiscal year for a company. */
public record OpenFiscalYearRequest(
        String companyUid,
        String yearCode,
        int startMonth,
        int calendarYear
) {}
