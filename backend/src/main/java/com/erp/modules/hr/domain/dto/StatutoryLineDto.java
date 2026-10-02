package com.erp.modules.hr.domain.dto;

import java.math.BigDecimal;

/**
 * One employee's statutory figures within a payroll run (FR-HR-23) — the row a TRA (PAYE/SDL),
 * NSSF, WCF or HESLB schedule is filled from. Employee number/name/department are the run's
 * snapshot; the statutory identifiers (TIN, NSSF and HESLB numbers) are the employee record's
 * current values and are null where none was captured.
 */
public record StatutoryLineDto(
        String employeeNumber,
        String employeeName,
        String departmentName,
        String tin,
        String nssfNumber,
        String heslbNumber,
        BigDecimal grossAmount,
        BigDecimal payeAmount,
        BigDecimal nssfEmployeeAmount,
        BigDecimal nssfEmployerAmount,
        BigDecimal wcfAmount,
        BigDecimal sdlAmount,
        BigDecimal heslbAmount,
        BigDecimal netAmount
) {}
