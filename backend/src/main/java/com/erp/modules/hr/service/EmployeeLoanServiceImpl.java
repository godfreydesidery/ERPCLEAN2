package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreateLoanRequest;
import com.erp.modules.hr.domain.dto.EmployeeLoanDto;
import com.erp.modules.hr.domain.entity.Employee;
import com.erp.modules.hr.domain.entity.EmployeeLoan;
import com.erp.modules.hr.domain.enums.LoanStatus;
import com.erp.modules.hr.repository.EmployeeLoanRepository;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class EmployeeLoanServiceImpl implements EmployeeLoanService {

    private final EmployeeLoanRepository loans;
    private final EmployeeRepository     employees;
    private final HrNumberGenerator      numberGenerator;
    private final ScopeGuard             scopeGuard;
    private final AuditService           audit;

    public EmployeeLoanServiceImpl(EmployeeLoanRepository loans,
                                    EmployeeRepository employees,
                                    HrNumberGenerator numberGenerator,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.loans           = loans;
        this.employees       = employees;
        this.numberGenerator = numberGenerator;
        this.scopeGuard      = scopeGuard;
        this.audit           = audit;
    }

    @Override
    public EmployeeLoanDto create(String employeeUid, CreateLoanRequest req) {
        RequestContext.Principal p = RequestContext.get();
        Employee emp = employees.findByUid(employeeUid)
                .orElseThrow(() -> NotFoundException.of("Employee", employeeUid));
        scopeGuard.assertCanActIn(p, emp.getCompanyId());

        String loanNumber = numberGenerator.nextLoan(emp.getCompanyId());
        String currency   = req.currency() != null ? req.currency() : "TZS";
        EmployeeLoan loan = new EmployeeLoan(emp.getCompanyId(), emp.getId(), loanNumber,
                req.principalAmount(), req.installmentAmount(),
                req.glAccountId(), req.startDate(), currency, p.userId());
        loans.save(loan);
        audit.record(AuditEvent.of(AuditActions.HR_LOAN_CREATE, "employee_loans", loan.getId(), null));
        return toDto(loan, emp.getFullName());
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeLoanDto getByUid(String uid) {
        EmployeeLoan loan = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), loan.getCompanyId());
        String empName = employees.findById(loan.getEmployeeId()).map(Employee::getFullName).orElse(null);
        return toDto(loan, empName);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EmployeeLoanDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return loans.findByCompanyId(companyId, pageable).map(loan -> {
            String empName = employees.findById(loan.getEmployeeId()).map(Employee::getFullName).orElse(null);
            return toDto(loan, empName);
        });
    }

    @Override
    public EmployeeLoanDto approve(String uid) {
        RequestContext.Principal p = RequestContext.get();
        EmployeeLoan loan = requireByUid(uid);
        scopeGuard.assertCanActIn(p, loan.getCompanyId());
        loan.setStatus(LoanStatus.ACTIVE);
        loan.setUpdatedAt(Instant.now());
        loan.setUpdatedBy(p.userId());
        audit.record(AuditEvent.of(AuditActions.HR_LOAN_APPROVE, "employee_loans", loan.getId(), null));
        String empName = employees.findById(loan.getEmployeeId()).map(Employee::getFullName).orElse(null);
        return toDto(loan, empName);
    }

    @Override
    public void close(String uid) {
        EmployeeLoan loan = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), loan.getCompanyId());
        loan.setStatus(LoanStatus.SETTLED);
        loan.setUpdatedAt(Instant.now());
        loan.setUpdatedBy(RequestContext.get().userId());
    }

    private EmployeeLoan requireByUid(String uid) {
        return loans.findByUid(uid).orElseThrow(() -> NotFoundException.of("EmployeeLoan", uid));
    }

    private EmployeeLoanDto toDto(EmployeeLoan l, String empName) {
        return new EmployeeLoanDto(l.getId(), l.getUid(), l.getCompanyId(), l.getEmployeeId(),
                empName, l.getLoanNumber(), l.getPrincipalAmount(),
                l.getInstallmentAmount(), l.getOutstandingAmount(),
                l.getGlAccountId(), l.getStatus(), l.getStartDate(), l.getCurrency());
    }
}
