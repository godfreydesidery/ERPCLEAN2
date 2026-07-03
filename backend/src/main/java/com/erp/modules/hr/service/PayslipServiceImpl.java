package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.PayslipDto;
import com.erp.modules.hr.domain.entity.Employee;
import com.erp.modules.hr.domain.entity.Payslip;
import com.erp.modules.hr.domain.entity.PayrollRun;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.modules.hr.repository.PayrollRunRepository;
import com.erp.modules.hr.repository.PayslipRepository;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PayslipServiceImpl implements PayslipService {

    private final PayslipRepository    payslips;
    private final PayrollRunRepository runs;
    private final EmployeeRepository   employees;
    private final ScopeGuard           scopeGuard;

    public PayslipServiceImpl(PayslipRepository payslips,
                               PayrollRunRepository runs,
                               EmployeeRepository employees,
                               ScopeGuard scopeGuard) {
        this.payslips   = payslips;
        this.runs       = runs;
        this.employees  = employees;
        this.scopeGuard = scopeGuard;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayslipDto> listByPayrollRunUid(String runUid) {
        PayrollRun run = Lookups.orNotFound(runs.findByUid(runUid), "PayrollRun", runUid);
        scopeGuard.assertCanActIn(RequestContext.get(), run.getCompanyId());

        return payslips.findByPayrollRunId(run.getId()).stream()
                .map(this::toDto)
                .sorted(Comparator.comparing(PayslipDto::employeeName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PayslipDto getByUid(String uid) {
        Payslip slip = Lookups.orNotFound(payslips.findByUid(uid), "Payslip", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), slip.getCompanyId());
        return toDto(slip);
    }

    // --- private helpers ---

    private PayslipDto toDto(Payslip s) {
        Employee emp = employees.findById(s.getEmployeeId()).orElse(null);
        String employeeName   = emp != null ? emp.getFullName() : null;
        String employeeNumber = emp != null ? emp.getEmployeeNumber() : null;

        return new PayslipDto(s.getId(), s.getUid(), s.getCompanyId(), s.getPayrollRunId(),
                s.getPayrollLineId(), s.getEmployeeId(), s.getPayslipNumber(), s.getPayDate(),
                s.getGrossAmount(), s.getDeductionAmount(), s.getNetAmount(), s.getEmployerCostAmount(),
                s.getYtdGross(), s.getYtdPaye(), s.getYtdNssfEmployee(), s.getYtdNet(),
                employeeName, employeeNumber);
    }
}
