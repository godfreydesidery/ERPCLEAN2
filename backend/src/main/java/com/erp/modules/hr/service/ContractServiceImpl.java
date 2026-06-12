package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.ContractDto;
import com.erp.modules.hr.domain.dto.CreateContractRequest;
import com.erp.modules.hr.domain.entity.Employee;
import com.erp.modules.hr.domain.entity.EmploymentContract;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.modules.hr.repository.EmploymentContractRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ContractServiceImpl implements ContractService {

    private final EmploymentContractRepository contracts;
    private final EmployeeRepository employees;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public ContractServiceImpl(EmploymentContractRepository contracts,
                                EmployeeRepository employees,
                                ScopeGuard scopeGuard,
                                AuditService audit) {
        this.contracts  = contracts;
        this.employees  = employees;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public ContractDto create(CreateContractRequest req) {
        RequestContext.Principal p = RequestContext.get();
        Long companyId = p.companyId();
        scopeGuard.assertCanActIn(p, companyId);

        // Validate that there is no other active contract for this employee
        // employeeId must be passed via URL (service call comes from controller)
        throw new UnsupportedOperationException("Call createForEmployee instead.");
    }

    /** Use this instead — controller passes employeeUid separately. */
    @Transactional
    public ContractDto createForEmployee(String employeeUid, CreateContractRequest req) {
        RequestContext.Principal p = RequestContext.get();
        Employee emp = employees.findByUid(employeeUid)
                .orElseThrow(() -> NotFoundException.of("Employee", employeeUid));
        scopeGuard.assertCanActIn(p, emp.getCompanyId());

        if (contracts.findByCompanyIdAndEmployeeIdAndActiveTrue(emp.getCompanyId(), emp.getId()).isPresent()) {
            throw new ConflictException("Employee already has an active contract.");
        }

        EmploymentContract c = new EmploymentContract(
                emp.getCompanyId(), emp.getId(), req.contractType(),
                req.baseSalaryAmount(), "TZS",
                req.startDate(), req.endDate(), p.userId());
        c.setPayeResident(req.payeResident());
        c.setNssfMember(req.nssfMember());
        c.setHeslbBorrower(req.heslbBorrower());
        c.setWcfCovered(req.wcfCovered());
        c.setSdlCounted(req.sdlCounted());
        contracts.save(c);

        audit.record(AuditEvent.of(AuditActions.HR_CONTRACT_CREATE, "employment_contracts", c.getId(), null));
        return toDto(c);
    }

    @Override
    @Transactional(readOnly = true)
    public ContractDto getByUid(String uid) {
        EmploymentContract c = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), c.getCompanyId());
        return toDto(c);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ContractDto> listByEmployee(Long companyId, Long employeeId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return contracts.findByCompanyIdAndEmployeeId(companyId, employeeId)
                .stream().map(this::toDto).toList();
    }

    @Override
    public void terminate(String uid) {
        EmploymentContract c = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), c.getCompanyId());
        c.setActive(false);
        c.setUpdatedAt(Instant.now());
        c.setUpdatedBy(RequestContext.get().userId());
        audit.record(AuditEvent.of(AuditActions.HR_CONTRACT_TERMINATE, "employment_contracts", c.getId(), null));
    }

    private EmploymentContract requireByUid(String uid) {
        return contracts.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("EmploymentContract", uid));
    }

    private ContractDto toDto(EmploymentContract c) {
        return new ContractDto(c.getId(), c.getUid(), c.getCompanyId(), c.getEmployeeId(),
                c.getContractType(), c.getBaseSalaryAmount(), c.getCurrency(), c.getPayFrequency(),
                c.getStartDate(), c.getEndDate(),
                c.isPayeResident(), c.isNssfMember(), c.isHeslbBorrower(),
                c.isWcfCovered(), c.isSdlCounted(), c.isActive());
    }
}
