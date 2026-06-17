package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreateEmployeeRequest;
import com.erp.modules.hr.domain.dto.EmployeeDto;
import com.erp.modules.hr.domain.entity.Department;
import com.erp.modules.hr.domain.entity.Employee;
import com.erp.modules.hr.domain.enums.EmploymentStatus;
import com.erp.modules.hr.domain.enums.PaymentMethod;
import com.erp.modules.hr.repository.DepartmentRepository;
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
public class EmployeeServiceImpl implements EmployeeService {

    private final EmployeeRepository   employees;
    private final DepartmentRepository departments;
    private final HrNumberGenerator    numberGenerator;
    private final ScopeGuard           scopeGuard;
    private final AuditService         audit;

    public EmployeeServiceImpl(EmployeeRepository employees,
                                DepartmentRepository departments,
                                HrNumberGenerator numberGenerator,
                                ScopeGuard scopeGuard,
                                AuditService audit) {
        this.employees       = employees;
        this.departments     = departments;
        this.numberGenerator = numberGenerator;
        this.scopeGuard      = scopeGuard;
        this.audit           = audit;
    }

    @Override
    public EmployeeDto create(CreateEmployeeRequest req) {
        RequestContext.Principal p = RequestContext.get();
        Long companyId = p.companyId();
        scopeGuard.assertCanActIn(p, companyId);

        validatePayeeTarget(req);

        String number = generateEmployeeNumber(companyId);
        Employee emp = new Employee(companyId, req.branchId(), number,
                req.firstName(), req.lastName(), req.hireDate(), p.userId());
        applyFields(emp, req);
        employees.save(emp);

        audit.record(AuditEvent.of(AuditActions.HR_EMPLOYEE_CREATE, "employees", emp.getId(), null));
        return toDto(emp, deptName(emp.getDepartmentId()));
    }

    @Override
    @Transactional(readOnly = true)
    public EmployeeDto getByUid(String uid) {
        Employee emp = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), emp.getCompanyId());
        return toDto(emp, deptName(emp.getDepartmentId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<EmployeeDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return employees.findByCompanyId(companyId, pageable)
                .map(e -> toDto(e, deptName(e.getDepartmentId())));
    }

    @Override
    public EmployeeDto update(String uid, CreateEmployeeRequest req) {
        Employee emp = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), emp.getCompanyId());
        RequestContext.Principal p = RequestContext.get();

        validatePayeeTarget(req);
        applyFields(emp, req);
        emp.setUpdatedAt(Instant.now());
        emp.setUpdatedBy(p.userId());

        audit.record(AuditEvent.of(AuditActions.HR_EMPLOYEE_UPDATE, "employees", emp.getId(), null));
        return toDto(emp, deptName(emp.getDepartmentId()));
    }

    @Override
    public void archive(String uid) {
        Employee emp = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), emp.getCompanyId());
        emp.setStatus(EmploymentStatus.TERMINATED);
        emp.setUpdatedAt(Instant.now());
        emp.setUpdatedBy(RequestContext.get().userId());
        audit.record(AuditEvent.of(AuditActions.HR_EMPLOYEE_ARCHIVE, "employees", emp.getId(), null));
    }

    // --- helpers ---

    /**
     * Validate that the payee target required by the chosen payment_method is present.
     * BANK_TRANSFER requires bank_account_no; MOBILE_MONEY requires mobile_money_no.
     */
    private void validatePayeeTarget(CreateEmployeeRequest req) {
        if (req.paymentMethod() == null) return;
        if (req.paymentMethod() == PaymentMethod.BANK_TRANSFER
                && (req.bankAccountNo() == null || req.bankAccountNo().isBlank())) {
            throw new IllegalArgumentException(
                    "bank_account_no is required when payment_method is BANK_TRANSFER");
        }
        if (req.paymentMethod() == PaymentMethod.MOBILE_MONEY
                && (req.mobileMoneyNo() == null || req.mobileMoneyNo().isBlank())) {
            throw new IllegalArgumentException(
                    "mobile_money_no is required when payment_method is MOBILE_MONEY");
        }
    }

    private void applyFields(Employee emp, CreateEmployeeRequest req) {
        emp.setFirstName(req.firstName());
        emp.setLastName(req.lastName());
        emp.setNationalId(req.nationalId());
        emp.setTin(req.tin());
        emp.setNssfNumber(req.nssfNumber());
        emp.setHeslbNumber(req.heslbNumber());
        emp.setDateOfBirth(req.dateOfBirth());
        emp.setGender(req.gender());
        emp.setDepartmentId(req.departmentId());
        emp.setJobTitle(req.jobTitle());
        emp.setBranchId(req.branchId());
        emp.setUserId(req.userId());
        // contact
        emp.setPhone(req.phone());
        emp.setEmail(req.email());
        emp.setAddressLine(req.addressLine());
        emp.setRegion(req.region());
        emp.setDistrict(req.district());
        emp.setPostalAddress(req.postalAddress());
        // payee
        emp.setPaymentMethod(req.paymentMethod());
        emp.setBankName(req.bankName());
        emp.setBankBranch(req.bankBranch());
        emp.setBankAccountNo(req.bankAccountNo());
        emp.setBankAccountName(req.bankAccountName());
        emp.setMobileMoneyNo(req.mobileMoneyNo());
    }

    private Employee requireByUid(String uid) {
        return employees.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("Employee", uid));
    }

    private String generateEmployeeNumber(Long companyId) {
        long count = employees.countByCompanyId(companyId) + 1;
        return String.format("EMP-%06d", count);
    }

    private String deptName(Long deptId) {
        if (deptId == null) return null;
        return departments.findById(deptId).map(Department::getName).orElse(null);
    }

    private EmployeeDto toDto(Employee e, String deptName) {
        return new EmployeeDto(e.getId(), e.getUid(), e.getCompanyId(), e.getBranchId(),
                e.getEmployeeNumber(), e.getFirstName(), e.getLastName(),
                e.getNationalId(), e.getTin(), e.getNssfNumber(), e.getHeslbNumber(),
                e.getDateOfBirth(), e.getGender(), e.getHireDate(),
                e.getDepartmentId(), deptName, e.getJobTitle(), e.getStatus(), e.getUserId(),
                e.getPhone(), e.getEmail(), e.getAddressLine(),
                e.getRegion(), e.getDistrict(), e.getPostalAddress(),
                e.getPaymentMethod(), e.getBankName(), e.getBankBranch(),
                e.getBankAccountNo(), e.getBankAccountName(), e.getMobileMoneyNo());
    }
}
