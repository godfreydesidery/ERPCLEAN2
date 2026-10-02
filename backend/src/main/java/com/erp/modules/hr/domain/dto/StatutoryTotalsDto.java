package com.erp.modules.hr.domain.dto;

import java.math.BigDecimal;

/** Totals across the payroll runs of a period in the Payroll Statutory report (FR-HR-23). */
public record StatutoryTotalsDto(
        int runCount,
        int payslipCount,
        BigDecimal grossTotal,
        BigDecimal payeTotal,
        BigDecimal nssfEmployeeTotal,
        BigDecimal nssfEmployerTotal,
        BigDecimal wcfTotal,
        BigDecimal sdlTotal,
        BigDecimal heslbTotal,
        BigDecimal netTotal,
        BigDecimal employerCostTotal
) {}
