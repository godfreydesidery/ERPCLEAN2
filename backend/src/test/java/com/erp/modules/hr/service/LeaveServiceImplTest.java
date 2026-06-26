package com.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.hr.domain.dto.DecideLeaveRequest;
import com.erp.modules.hr.domain.dto.SubmitLeaveRequest;
import com.erp.modules.hr.domain.entity.Employee;
import com.erp.modules.hr.domain.entity.LeaveRequest;
import com.erp.modules.hr.domain.entity.LeaveType;
import com.erp.modules.hr.domain.enums.EmploymentStatus;
import com.erp.modules.hr.domain.enums.LeaveAccrualMethod;
import com.erp.modules.hr.domain.enums.LeaveRequestStatus;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.modules.hr.repository.LeaveRequestRepository;
import com.erp.modules.hr.repository.LeaveTypeRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LeaveServiceImpl} covering the three state guards added
 * for defects D1, D2, and D3.
 */
class LeaveServiceImplTest {

    private LeaveRequestRepository leaveRequests;
    private LeaveTypeRepository    leaveTypes;
    private EmployeeRepository     employees;
    private ScopeGuard             scopeGuard;
    private AuditService           audit;
    private LeaveServiceImpl       service;

    private static final Long   COMPANY_ID   = 10L;
    private static final Long   EMPLOYEE_ID  = 1L;
    private static final String EMPLOYEE_UID = "01EMPUIDTEST0000000000001";
    private static final Long   LEAVE_TYPE_ID = 5L;
    private static final String LR_UID       = "01LRUIDTEST00000000000001";

    @BeforeEach
    void setUp() {
        leaveRequests = mock(LeaveRequestRepository.class);
        leaveTypes    = mock(LeaveTypeRepository.class);
        employees     = mock(EmployeeRepository.class);
        scopeGuard    = mock(ScopeGuard.class);
        audit         = mock(AuditService.class);
        service = new LeaveServiceImpl(leaveRequests, leaveTypes, employees, scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(99L, "hr@erp.com", false, COMPANY_ID, null, null));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // D1 — terminated employee is rejected (409 via ConflictException)
    // -----------------------------------------------------------------------

    @Test
    void submit_terminatedEmployee_throwsConflict() {
        Employee emp = makeEmployee(EmploymentStatus.TERMINATED);
        when(employees.findByUid(EMPLOYEE_UID)).thenReturn(Optional.of(emp));

        SubmitLeaveRequest req = new SubmitLeaveRequest(LEAVE_TYPE_ID,
                LocalDate.now(), LocalDate.now().plusDays(2), BigDecimal.valueOf(3), "holiday");

        assertThatThrownBy(() -> service.submit(EMPLOYEE_UID, req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("TERMINATED");

        verify(leaveRequests, never()).save(any());
    }

    @Test
    void submit_suspendedEmployee_throwsConflict() {
        Employee emp = makeEmployee(EmploymentStatus.SUSPENDED);
        when(employees.findByUid(EMPLOYEE_UID)).thenReturn(Optional.of(emp));

        SubmitLeaveRequest req = new SubmitLeaveRequest(LEAVE_TYPE_ID,
                LocalDate.now(), LocalDate.now().plusDays(1), BigDecimal.ONE, null);

        assertThatThrownBy(() -> service.submit(EMPLOYEE_UID, req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("SUSPENDED");

        verify(leaveRequests, never()).save(any());
    }

    @Test
    void submit_activeEmployee_proceeds() {
        Employee emp = makeEmployee(EmploymentStatus.ACTIVE);
        when(employees.findByUid(EMPLOYEE_UID)).thenReturn(Optional.of(emp));

        LeaveType lt = makeLeaveType(true);
        when(leaveTypes.findByIdAndCompanyId(LEAVE_TYPE_ID, COMPANY_ID)).thenReturn(Optional.of(lt));

        LeaveRequest saved = new LeaveRequest(COMPANY_ID, EMPLOYEE_ID, LEAVE_TYPE_ID,
                LocalDate.now(), LocalDate.now().plusDays(1), BigDecimal.ONE, null, 99L);
        when(leaveRequests.save(any())).thenReturn(saved);

        SubmitLeaveRequest req = new SubmitLeaveRequest(LEAVE_TYPE_ID,
                LocalDate.now(), LocalDate.now().plusDays(1), BigDecimal.ONE, null);

        service.submit(EMPLOYEE_UID, req); // must not throw

        verify(leaveRequests).save(any());
    }

    // -----------------------------------------------------------------------
    // D2 — inactive leave type is rejected (409 via ConflictException)
    // -----------------------------------------------------------------------

    @Test
    void submit_inactiveLeaveType_throwsConflict() {
        Employee emp = makeEmployee(EmploymentStatus.ACTIVE);
        when(employees.findByUid(EMPLOYEE_UID)).thenReturn(Optional.of(emp));

        LeaveType lt = makeLeaveType(false);
        when(leaveTypes.findByIdAndCompanyId(LEAVE_TYPE_ID, COMPANY_ID)).thenReturn(Optional.of(lt));

        SubmitLeaveRequest req = new SubmitLeaveRequest(LEAVE_TYPE_ID,
                LocalDate.now(), LocalDate.now().plusDays(1), BigDecimal.ONE, null);

        assertThatThrownBy(() -> service.submit(EMPLOYEE_UID, req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not active");

        verify(leaveRequests, never()).save(any());
    }

    @Test
    void submit_activeLeaveType_proceeds() {
        Employee emp = makeEmployee(EmploymentStatus.ACTIVE);
        when(employees.findByUid(EMPLOYEE_UID)).thenReturn(Optional.of(emp));

        LeaveType lt = makeLeaveType(true);
        when(leaveTypes.findByIdAndCompanyId(LEAVE_TYPE_ID, COMPANY_ID)).thenReturn(Optional.of(lt));

        LeaveRequest saved = new LeaveRequest(COMPANY_ID, EMPLOYEE_ID, LEAVE_TYPE_ID,
                LocalDate.now(), LocalDate.now().plusDays(1), BigDecimal.ONE, null, 99L);
        when(leaveRequests.save(any())).thenReturn(saved);

        SubmitLeaveRequest req = new SubmitLeaveRequest(LEAVE_TYPE_ID,
                LocalDate.now(), LocalDate.now().plusDays(1), BigDecimal.ONE, null);

        service.submit(EMPLOYEE_UID, req); // must not throw
        verify(leaveRequests).save(any());
    }

    // -----------------------------------------------------------------------
    // D3 — re-approving/rejecting an already-decided request is rejected (409)
    // -----------------------------------------------------------------------

    @Test
    void decide_alreadyApproved_throwsIllegalState() {
        LeaveRequest lr = makeLeaveRequest(LeaveRequestStatus.APPROVED);
        when(leaveRequests.findByUid(LR_UID)).thenReturn(Optional.of(lr));

        DecideLeaveRequest req = new DecideLeaveRequest(LeaveRequestStatus.APPROVED, null);

        assertThatThrownBy(() -> service.decide(LR_UID, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APPROVED");
    }

    @Test
    void decide_alreadyRejected_throwsIllegalState() {
        LeaveRequest lr = makeLeaveRequest(LeaveRequestStatus.REJECTED);
        when(leaveRequests.findByUid(LR_UID)).thenReturn(Optional.of(lr));

        DecideLeaveRequest req = new DecideLeaveRequest(LeaveRequestStatus.REJECTED, null);

        assertThatThrownBy(() -> service.decide(LR_UID, req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REJECTED");
    }

    @Test
    void decide_pendingRequest_approved_succeeds() {
        LeaveRequest lr = makeLeaveRequest(LeaveRequestStatus.PENDING);
        when(leaveRequests.findByUid(LR_UID)).thenReturn(Optional.of(lr));
        when(employees.findById(EMPLOYEE_ID)).thenReturn(Optional.of(makeEmployee(EmploymentStatus.ACTIVE)));
        when(leaveTypes.findById(LEAVE_TYPE_ID)).thenReturn(Optional.of(makeLeaveType(true)));

        DecideLeaveRequest req = new DecideLeaveRequest(LeaveRequestStatus.APPROVED, "OK");

        service.decide(LR_UID, req); // must not throw

        // status should have been updated
        org.assertj.core.api.Assertions.assertThat(lr.getStatus())
                .isEqualTo(LeaveRequestStatus.APPROVED);
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private Employee makeEmployee(EmploymentStatus status) {
        Employee emp = new Employee(COMPANY_ID, 2L, "EMP-000001", "John", "Doe",
                LocalDate.of(2020, 1, 1), 1L);
        emp.setStatus(status);
        setId(emp, EMPLOYEE_ID);
        return emp;
    }

    private LeaveType makeLeaveType(boolean active) {
        LeaveType lt = new LeaveType(COMPANY_ID, "AL", "Annual Leave", true,
                BigDecimal.valueOf(21), LeaveAccrualMethod.ANNUAL_GRANT, 1L);
        setId(lt, LEAVE_TYPE_ID);
        lt.setActive(active);
        return lt;
    }

    private LeaveRequest makeLeaveRequest(LeaveRequestStatus status) {
        LeaveRequest lr = new LeaveRequest(COMPANY_ID, EMPLOYEE_ID, LEAVE_TYPE_ID,
                LocalDate.now(), LocalDate.now().plusDays(1), BigDecimal.ONE, null, 1L);
        setId(lr, 42L);
        lr.setStatus(status);
        try {
            java.lang.reflect.Method m = com.erp.platform.common.domain.UidEntity.class
                    .getDeclaredMethod("setUid", String.class);
            m.setAccessible(true);
            m.invoke(lr, LR_UID);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return lr;
    }

    private static void setId(Object target, Long value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField("id");
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Field 'id' not found on " + target.getClass());
    }
}
