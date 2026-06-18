package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.DecideLeaveRequest;
import com.erp.modules.hr.domain.dto.LeaveRequestDto;
import com.erp.modules.hr.domain.dto.SubmitLeaveRequest;
import com.erp.modules.hr.domain.entity.Employee;
import com.erp.modules.hr.domain.entity.LeaveRequest;
import com.erp.modules.hr.domain.entity.LeaveType;
import com.erp.modules.hr.domain.enums.LeaveRequestStatus;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.modules.hr.repository.LeaveRequestRepository;
import com.erp.modules.hr.repository.LeaveTypeRepository;
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
public class LeaveServiceImpl implements LeaveService {

    private final LeaveRequestRepository leaveRequests;
    private final LeaveTypeRepository    leaveTypes;
    private final EmployeeRepository     employees;
    private final ScopeGuard             scopeGuard;
    private final AuditService           audit;

    public LeaveServiceImpl(LeaveRequestRepository leaveRequests,
                             LeaveTypeRepository leaveTypes,
                             EmployeeRepository employees,
                             ScopeGuard scopeGuard,
                             AuditService audit) {
        this.leaveRequests = leaveRequests;
        this.leaveTypes    = leaveTypes;
        this.employees     = employees;
        this.scopeGuard    = scopeGuard;
        this.audit         = audit;
    }

    @Override
    public LeaveRequestDto submit(String employeeUid, SubmitLeaveRequest req) {
        RequestContext.Principal p = RequestContext.get();
        Employee emp = employees.findByUid(employeeUid)
                .orElseThrow(() -> NotFoundException.of("Employee", employeeUid));
        scopeGuard.assertCanActIn(p, emp.getCompanyId());

        LeaveType leaveType = leaveTypes.findById(req.leaveTypeId())
                .orElseThrow(() -> NotFoundException.of("LeaveType", String.valueOf(req.leaveTypeId())));

        LeaveRequest lr = new LeaveRequest(emp.getCompanyId(), emp.getId(),
                req.leaveTypeId(), req.fromDate(), req.toDate(), req.days(), req.reason(), p.userId());
        leaveRequests.save(lr);
        audit.record(AuditEvent.of(AuditActions.HR_LEAVE_REQUEST_SUBMIT, "leave_requests", lr.getId(), null));

        return toDto(lr, emp.getFullName(), leaveType.getName());
    }

    @Override
    @Transactional(readOnly = true)
    public LeaveRequestDto getByUid(String uid) {
        LeaveRequest lr = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), lr.getCompanyId());
        String empName = employees.findById(lr.getEmployeeId()).map(Employee::getFullName).orElse(null);
        String ltName  = leaveTypes.findById(lr.getLeaveTypeId()).map(LeaveType::getName).orElse(null);
        return toDto(lr, empName, ltName);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LeaveRequestDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return leaveRequests.findByCompanyId(companyId, pageable)
                .map(lr -> {
                    String empName = employees.findById(lr.getEmployeeId()).map(Employee::getFullName).orElse(null);
                    String ltName  = leaveTypes.findById(lr.getLeaveTypeId()).map(LeaveType::getName).orElse(null);
                    return toDto(lr, empName, ltName);
                });
    }

    @Override
    public LeaveRequestDto decide(String uid, DecideLeaveRequest req) {
        RequestContext.Principal p = RequestContext.get();
        LeaveRequest lr = requireByUid(uid);
        scopeGuard.assertCanActIn(p, lr.getCompanyId());

        if (req.decision() != LeaveRequestStatus.APPROVED && req.decision() != LeaveRequestStatus.REJECTED) {
            throw new IllegalArgumentException("Decision must be APPROVED or REJECTED.");
        }
        lr.setStatus(req.decision());
        lr.setDecisionNote(req.decisionNote());
        lr.setDecidedBy(p.userId());
        lr.setDecidedAt(Instant.now());
        lr.setUpdatedAt(Instant.now());
        lr.setUpdatedBy(p.userId());
        audit.record(AuditEvent.of(AuditActions.HR_LEAVE_REQUEST_DECIDE, "leave_requests", lr.getId(), null));

        String empName = employees.findById(lr.getEmployeeId()).map(Employee::getFullName).orElse(null);
        String ltName  = leaveTypes.findById(lr.getLeaveTypeId()).map(LeaveType::getName).orElse(null);
        return toDto(lr, empName, ltName);
    }

    private LeaveRequest requireByUid(String uid) {
        return leaveRequests.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("LeaveRequest", uid));
    }

    private LeaveRequestDto toDto(LeaveRequest lr, String empName, String ltName) {
        return new LeaveRequestDto(lr.getId(), lr.getUid(), lr.getCompanyId(), lr.getEmployeeId(),
                empName, lr.getLeaveTypeId(), ltName,
                lr.getFromDate(), lr.getToDate(), lr.getDays(),
                lr.getStatus(), lr.getDecidedBy(), lr.getDecidedAt(),
                lr.getReason(), lr.getDecisionNote());
    }
}
