package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreatePayrollRunRequest;
import com.erp.modules.hr.domain.dto.DisburseRequest;
import com.erp.modules.hr.domain.dto.PayrollLineDto;
import com.erp.modules.hr.domain.dto.PayrollRunDto;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PayrollRunService {

    /** Create a DRAFT payroll run for the period. */
    PayrollRunDto create(CreatePayrollRunRequest req);

    PayrollRunDto getByUid(String uid);

    Page<PayrollRunDto> listByCompany(Long companyId, Pageable pageable);

    /** Calculate/recalculate all lines for a DRAFT run. */
    PayrollRunDto calculate(String uid);

    /** Move CALCULATED → APPROVED. */
    PayrollRunDto approve(String uid);

    /** Post to GL via outbox (APPROVED → POSTED). */
    PayrollRunDto post(String uid);

    /** Disburse net wages via Cash & Bank (POSTED → PAID). */
    PayrollRunDto disburse(String uid, DisburseRequest req);

    /** Reverse a POSTED or PAID run (posts reversal via outbox). */
    PayrollRunDto reverse(String uid);

    List<PayrollLineDto> listLines(String uid);

    /**
     * Export the per-employee EFT batch as CSV text for a run that has been disbursed (PAID status
     * is not enforced — callers may export from POSTED onwards). Columns:
     * employee_number, employee_name, payee_method, payee_bank_name, payee_account_name,
     * payee_account_ref, net_amount, currency (ADR-0040 D-11).
     */
    String exportEftBatch(String runUid);
}
