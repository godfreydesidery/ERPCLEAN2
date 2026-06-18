package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreateDepartmentRequest;
import com.erp.modules.hr.domain.dto.DepartmentDto;
import com.erp.modules.hr.domain.entity.Department;
import com.erp.modules.hr.repository.DepartmentRepository;
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
public class DepartmentServiceImpl implements DepartmentService {

    private final DepartmentRepository departments;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public DepartmentServiceImpl(DepartmentRepository departments,
                                  ScopeGuard scopeGuard,
                                  AuditService audit) {
        this.departments = departments;
        this.scopeGuard  = scopeGuard;
        this.audit       = audit;
    }

    @Override
    public DepartmentDto create(CreateDepartmentRequest req) {
        RequestContext.Principal p = RequestContext.get();
        Long companyId = p.companyId();
        scopeGuard.assertCanActIn(p, companyId);
        if (departments.existsByCompanyIdAndCode(companyId, req.code())) {
            throw new ConflictException("Department code already exists: " + req.code());
        }
        Department dept = departments.save(new Department(companyId, req.code(), req.name(), p.userId()));
        audit.record(AuditEvent.of(AuditActions.HR_DEPARTMENT_CREATE, "departments", dept.getId(), null));
        return toDto(dept);
    }

    @Override
    @Transactional(readOnly = true)
    public DepartmentDto getByUid(String uid) {
        Department dept = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), dept.getCompanyId());
        return toDto(dept);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentDto> listByCompany(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return departments.findByCompanyIdAndActiveTrue(companyId).stream().map(this::toDto).toList();
    }

    @Override
    public DepartmentDto update(String uid, CreateDepartmentRequest req) {
        Department dept = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), dept.getCompanyId());
        if (!dept.getCode().equals(req.code())
                && departments.existsByCompanyIdAndCode(dept.getCompanyId(), req.code())) {
            throw new ConflictException("Department code already exists: " + req.code());
        }
        RequestContext.Principal p = RequestContext.get();
        dept.setCode(req.code());
        dept.setName(req.name());
        dept.setUpdatedAt(Instant.now());
        dept.setUpdatedBy(p.userId());
        audit.record(AuditEvent.of(AuditActions.HR_DEPARTMENT_UPDATE, "departments", dept.getId(), null));
        return toDto(dept);
    }

    @Override
    public void deactivate(String uid) {
        Department dept = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), dept.getCompanyId());
        dept.setActive(false);
        dept.setUpdatedAt(Instant.now());
        dept.setUpdatedBy(RequestContext.get().userId());
    }

    private Department requireByUid(String uid) {
        return departments.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("Department", uid));
    }

    private DepartmentDto toDto(Department d) {
        return new DepartmentDto(d.getId(), d.getUid(), d.getCompanyId(), d.getCode(), d.getName(), d.isActive(),
                d.getParentDepartmentId(), d.getManagerId(), d.getCostCentreValueId(), d.getBranchId());
    }
}
