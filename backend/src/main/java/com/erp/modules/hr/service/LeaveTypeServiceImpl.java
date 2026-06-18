package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreateLeaveTypeRequest;
import com.erp.modules.hr.domain.dto.LeaveTypeDto;
import com.erp.modules.hr.domain.entity.LeaveType;
import com.erp.modules.hr.repository.LeaveTypeRepository;
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
public class LeaveTypeServiceImpl implements LeaveTypeService {

    private final LeaveTypeRepository leaveTypes;
    private final ScopeGuard          scopeGuard;
    private final AuditService         audit;

    public LeaveTypeServiceImpl(LeaveTypeRepository leaveTypes,
                                 ScopeGuard scopeGuard,
                                 AuditService audit) {
        this.leaveTypes = leaveTypes;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public LeaveTypeDto create(CreateLeaveTypeRequest req) {
        RequestContext.Principal p = RequestContext.get();
        Long companyId = p.companyId();
        scopeGuard.assertCanActIn(p, companyId);
        if (leaveTypes.existsByCompanyIdAndCode(companyId, req.code())) {
            throw new ConflictException("Leave type code already exists: " + req.code());
        }
        LeaveType lt = new LeaveType(companyId, req.code(), req.name(), req.paid(),
                req.annualEntitlementDays(), req.accrualMethod(), p.userId());
        lt.setCarryForward(req.carryForward());
        lt.setRequiresApproval(req.requiresApproval());
        lt.setGenderEligibility(req.genderEligibility());
        lt.setMaxConsecutiveDays(req.maxConsecutiveDays());
        leaveTypes.save(lt);
        audit.record(AuditEvent.of(AuditActions.HR_LEAVE_TYPE_CREATE, "leave_types", lt.getId(), null));
        return toDto(lt);
    }

    @Override
    @Transactional(readOnly = true)
    public LeaveTypeDto getByUid(String uid) {
        LeaveType lt = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), lt.getCompanyId());
        return toDto(lt);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LeaveTypeDto> listByCompany(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return leaveTypes.findByCompanyIdAndActiveTrue(companyId).stream().map(this::toDto).toList();
    }

    @Override
    public LeaveTypeDto update(String uid, CreateLeaveTypeRequest req) {
        RequestContext.Principal p = RequestContext.get();
        LeaveType lt = requireByUid(uid);
        scopeGuard.assertCanActIn(p, lt.getCompanyId());
        if (!lt.getCode().equals(req.code())
                && leaveTypes.existsByCompanyIdAndCode(lt.getCompanyId(), req.code())) {
            throw new ConflictException("Leave type code already exists: " + req.code());
        }
        lt.setCode(req.code());
        lt.setName(req.name());
        lt.setPaid(req.paid());
        lt.setAnnualEntitlementDays(req.annualEntitlementDays());
        lt.setAccrualMethod(req.accrualMethod());
        lt.setCarryForward(req.carryForward());
        lt.setRequiresApproval(req.requiresApproval());
        lt.setGenderEligibility(req.genderEligibility());
        lt.setMaxConsecutiveDays(req.maxConsecutiveDays());
        lt.setUpdatedAt(Instant.now());
        lt.setUpdatedBy(p.userId());
        audit.record(AuditEvent.of(AuditActions.HR_LEAVE_TYPE_UPDATE, "leave_types", lt.getId(), null));
        return toDto(lt);
    }

    @Override
    public void deactivate(String uid) {
        RequestContext.Principal p = RequestContext.get();
        LeaveType lt = requireByUid(uid);
        scopeGuard.assertCanActIn(p, lt.getCompanyId());
        lt.setActive(false);
        lt.setUpdatedAt(Instant.now());
        lt.setUpdatedBy(p.userId());
    }

    private LeaveType requireByUid(String uid) {
        return leaveTypes.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("LeaveType", uid));
    }

    private LeaveTypeDto toDto(LeaveType lt) {
        return new LeaveTypeDto(lt.getId(), lt.getUid(), lt.getCompanyId(),
                lt.getCode(), lt.getName(), lt.isPaid(),
                lt.getAnnualEntitlementDays(), lt.getAccrualMethod(), lt.isActive(),
                lt.isCarryForward(), lt.isRequiresApproval(),
                lt.getGenderEligibility(), lt.getMaxConsecutiveDays());
    }
}
