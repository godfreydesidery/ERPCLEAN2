package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreateNextOfKinRequest;
import com.erp.modules.hr.domain.dto.EmployeeNextOfKinDto;
import com.erp.modules.hr.domain.dto.UpdateNextOfKinRequest;
import com.erp.modules.hr.domain.entity.Employee;
import com.erp.modules.hr.domain.entity.EmployeeNextOfKin;
import com.erp.modules.hr.repository.EmployeeNextOfKinRepository;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages next-of-kin child records owned by an employee (ADR-0040 D-11).
 *
 * <p>Primary rule: at most one ACTIVE next-of-kin per employee is primary. When a record is
 * created/updated with {@code isPrimary=true}, any existing primary for that employee is cleared
 * first (within the same transaction) — the clear-then-set pattern mirrored from the parties
 * contact/address CRUD. The partial-unique index {@code uq_employee_next_of_kin_primary} is the
 * DB-level backstop.
 */
@Service
@Transactional
public class EmployeeNextOfKinServiceImpl implements EmployeeNextOfKinService {

    private static final String INACTIVE = "INACTIVE";
    private static final String TARGET   = "employee_next_of_kin";

    private final EmployeeRepository          employees;
    private final EmployeeNextOfKinRepository nextOfKin;
    private final ScopeGuard                  scopeGuard;
    private final AuditService                audit;

    public EmployeeNextOfKinServiceImpl(EmployeeRepository employees,
                                        EmployeeNextOfKinRepository nextOfKin,
                                        ScopeGuard scopeGuard,
                                        AuditService audit) {
        this.employees  = employees;
        this.nextOfKin  = nextOfKin;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public EmployeeNextOfKinDto add(String employeeUid, CreateNextOfKinRequest req) {
        Employee employee = requireEmployee(employeeUid);
        if (req.isPrimary()) {
            clearPrimary(employee.getId());
        }
        EmployeeNextOfKin kin = new EmployeeNextOfKin(
                employee.getCompanyId(), employee.getId(), req.name(), actorId());
        kin.setRelationship(req.relationship());
        kin.setPhone(req.phone());
        kin.setEmail(req.email());
        kin.setPrimary(req.isPrimary());

        EmployeeNextOfKin saved = nextOfKin.save(kin);
        audit.record(AuditEvent.of(AuditActions.HR_EMPLOYEE_NOK_ADD, TARGET,
                        saved.getId(), saved.getUid())
                .detail(Map.of("employeeId", employee.getId(), "isPrimary", req.isPrimary())));
        return EmployeeNextOfKinDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EmployeeNextOfKinDto> list(String employeeUid) {
        Employee employee = requireEmployee(employeeUid);
        return nextOfKin.findByEmployeeId(employee.getId()).stream()
                .map(EmployeeNextOfKinDto::from)
                .toList();
    }

    @Override
    public EmployeeNextOfKinDto update(String employeeUid, String nokUid, UpdateNextOfKinRequest req) {
        Employee employee = requireEmployee(employeeUid);
        EmployeeNextOfKin kin = requireKin(nokUid, employee.getId());
        if (req.isPrimary() && !kin.isPrimary()) {
            clearPrimary(employee.getId());
        }
        kin.setName(req.name());
        kin.setRelationship(req.relationship());
        kin.setPhone(req.phone());
        kin.setEmail(req.email());
        kin.setPrimary(req.isPrimary());
        kin.setUpdatedAt(Instant.now());
        kin.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.HR_EMPLOYEE_NOK_UPDATE, TARGET,
                kin.getId(), kin.getUid()));
        return EmployeeNextOfKinDto.from(kin);
    }

    @Override
    public void deactivate(String employeeUid, String nokUid) {
        Employee employee = requireEmployee(employeeUid);
        EmployeeNextOfKin kin = requireKin(nokUid, employee.getId());
        kin.setStatus(INACTIVE);
        kin.setPrimary(false);
        kin.setUpdatedAt(Instant.now());
        kin.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.HR_EMPLOYEE_NOK_DEACTIVATE, TARGET,
                kin.getId(), kin.getUid()));
    }

    // --- helpers ---

    private Employee requireEmployee(String uid) {
        Employee e = Lookups.orNotFound(employees.findByUid(uid), "Employee", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), e.getCompanyId());
        return e;
    }

    private EmployeeNextOfKin requireKin(String nokUid, Long employeeId) {
        EmployeeNextOfKin kin = Lookups.orNotFound(
                nextOfKin.findByUid(nokUid), "EmployeeNextOfKin", nokUid);
        if (!kin.getEmployeeId().equals(employeeId)) {
            throw NotFoundException.of("EmployeeNextOfKin", nokUid);
        }
        return kin;
    }

    /** Clears the current primary next-of-kin for the given employee (if one exists). */
    private void clearPrimary(Long employeeId) {
        nextOfKin.findByEmployeeIdAndIsPrimaryTrue(employeeId).ifPresent(existing -> {
            existing.setPrimary(false);
            existing.setUpdatedAt(Instant.now());
            existing.setUpdatedBy(actorId());
        });
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
