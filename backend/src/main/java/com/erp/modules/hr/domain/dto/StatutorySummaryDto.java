package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.PayrollRunStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Per-run statutory totals for filing/reporting (ADR-0032 D-12, FR-HR-23).
 *
 * <p>Every figure is summed from the run's payroll lines — the same lines the payslips and the GL
 * posting are built from — so the summary cannot drift from what was paid. {@code employerCostTotal}
 * is NSSF (employer) + WCF + SDL, the same definition the payslip uses.
 */
public record StatutorySummaryDto(
        String runUid,
        String runNumber,
        int periodYear,
        int periodMonth,
        LocalDate payDate,
        PayrollRunStatus status,
        int employeeCount,
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
