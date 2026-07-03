package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.PayslipDto;
import java.util.List;

/**
 * Read-only access to generated payslips (ADR-0032 D-6). Payslips are produced as a side effect
 * of {@link PayrollRunService#post(String)} — this service only exposes them, name-enriched from
 * the employee master, for the HR/Payroll manager to review.
 */
public interface PayslipService {

    /** All payslips for a payroll run, ordered by employee name. */
    List<PayslipDto> listByPayrollRunUid(String runUid);

    /** A single payslip by its own uid. */
    PayslipDto getByUid(String uid);
}
