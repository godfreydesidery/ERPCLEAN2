package com.erp.modules.hr.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Per-run statutory summary for filing/reporting (ADR-0032 D-12). */
public record StatutorySummaryDto(
        String runUid,
        String runNumber,
        LocalDate payDate,
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
