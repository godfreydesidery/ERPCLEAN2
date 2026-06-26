package com.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.hr.domain.dto.CreateEmployeeRequest;
import com.erp.modules.hr.domain.dto.CreateLoanRequest;
import com.erp.modules.hr.domain.dto.CreatePayComponentRequest;
import com.erp.modules.hr.domain.dto.SubmitLeaveRequest;
import com.erp.modules.hr.domain.entity.Employee;
import com.erp.modules.hr.domain.enums.EmploymentStatus;
import com.erp.modules.hr.domain.enums.PayComponentBasis;
import com.erp.modules.hr.domain.enums.PayComponentKind;
import com.erp.modules.hr.repository.DepartmentRepository;
import com.erp.modules.hr.repository.EmployeeLoanRepository;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.modules.hr.repository.LeaveRequestRepository;
import com.erp.modules.hr.repository.LeaveTypeRepository;
import com.erp.modules.hr.repository.PayComponentRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.PermissionResolver;
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
 * Regression tests for the four confirmed CONFUSED_DEPUTY tenant-isolation bugs in the HR module.
 *
 * <p>Pattern under test: a Company-A caller has a legitimate scope context (employee, principal)
 * but supplies a foreign Company-B id for a cross-module FK (department, leave type, GL account).
 * The fix ensures those lookups are scoped to the company derived from the loaded entity — never
 * from the raw caller-supplied id — so the foreign FK is rejected with 404 (no existence leak).
 *
 * <p>These are pure Mockito unit tests; no Testcontainers required.  The corresponding
 * integration-test repro is:
 * <ol>
 *   <li>Create Company A (id=4) and Company B (id=5) with separate admins.
 *   <li>As B_ADMIN: create department id=17 (companyId=5), leave type id=5 (companyId=5),
 *       GL account id=144 (companyId=5).
 *   <li>As A_ADMIN (RequestContext companyId=4): attempt each write with the B-owned foreign id.
 *   <li>Assert HTTP 404 and that no row is persisted with the cross-tenant FK.
 * </ol>
 */
class HrCrossCompanyIsolationTest {

    // Company A owns the caller context and all legitimate A-entities.
    private static final Long   COMPANY_A      = 4L;
    // Company B owns the foreign resources the attacker is trying to link.
    private static final Long   COMPANY_B      = 5L;
    private static final Long   FOREIGN_DEPT_ID        = 17L;
    private static final Long   FOREIGN_LEAVE_TYPE_ID  = 5L;
    private static final Long   FOREIGN_GL_ACCOUNT_ID  = 144L;
    private static final String EMP_UID        = "01HZEMPLOYEEA000000000001";
    private static final Long   EMP_ID         = 1L;

    // -- shared mocks --
    private ScopeGuard           scopeGuard;
    private AuditService         audit;

    // -- per-service mocks and SUT instances --
    private DepartmentRepository  deptRepo;
    private EmployeeRepository    empRepo;
    private BranchRepository      branchRepo;
    private HrNumberGenerator     numberGenerator;
    private PermissionResolver    permissions;
    private EmployeeServiceImpl   employeeService;

    private LeaveTypeRepository   leaveTypeRepo;
    private LeaveRequestRepository leaveRequestRepo;
    private LeaveServiceImpl      leaveService;

    private PayComponentRepository payComponentRepo;
    private ChartOfAccountRepository glAccountRepo;
    private PayComponentServiceImpl  payComponentService;

    private EmployeeLoanRepository  loanRepo;
    private EmployeeLoanServiceImpl  loanService;

    @BeforeEach
    void setUp() {
        scopeGuard      = mock(ScopeGuard.class);
        audit           = mock(AuditService.class);
        deptRepo        = mock(DepartmentRepository.class);
        empRepo         = mock(EmployeeRepository.class);
        branchRepo      = mock(BranchRepository.class);
        numberGenerator = mock(HrNumberGenerator.class);
        permissions     = mock(PermissionResolver.class);
        glAccountRepo   = mock(ChartOfAccountRepository.class);

        employeeService = new EmployeeServiceImpl(
                empRepo, deptRepo, branchRepo, numberGenerator, scopeGuard, audit, permissions);

        leaveTypeRepo     = mock(LeaveTypeRepository.class);
        leaveRequestRepo  = mock(LeaveRequestRepository.class);
        leaveService = new LeaveServiceImpl(
                leaveRequestRepo, leaveTypeRepo, empRepo, scopeGuard, audit);

        payComponentRepo = mock(PayComponentRepository.class);
        payComponentService = new PayComponentServiceImpl(
                payComponentRepo, glAccountRepo, scopeGuard, audit);

        loanRepo = mock(EmployeeLoanRepository.class);
        loanService = new EmployeeLoanServiceImpl(
                loanRepo, empRepo, glAccountRepo, numberGenerator, scopeGuard, audit);

        // Caller is Company-A admin
        RequestContext.set(new RequestContext.Principal(99L, "a_admin@erp.com", false, COMPANY_A, 2L, "127.0.0.1"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Site 1 — Employee create: foreign-company departmentId rejected (POST /api/v1/hr/employees)
    // -----------------------------------------------------------------------

    @Test
    void createEmployee_withForeignCompanyDepartment_throwsNotFound() {
        // department id=17 exists globally but belongs to Company B, not Company A
        when(deptRepo.existsByIdAndCompanyId(FOREIGN_DEPT_ID, COMPANY_A)).thenReturn(false);

        CreateEmployeeRequest req = employeeRequest(FOREIGN_DEPT_ID);

        assertThatThrownBy(() -> employeeService.create(req))
                .isInstanceOf(NotFoundException.class);

        verify(empRepo, never()).save(any());
    }

    @Test
    void createEmployee_withOwnCompanyDepartment_succeeds() {
        Long ownDeptId = 7L;
        when(deptRepo.existsByIdAndCompanyId(ownDeptId, COMPANY_A)).thenReturn(true);
        when(branchRepo.existsById(2L)).thenReturn(true);
        when(numberGenerator.nextLoan(any())).thenReturn("LN-00001"); // not called here; just safe
        when(empRepo.countByCompanyId(COMPANY_A)).thenReturn(0L);
        when(empRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateEmployeeRequest req = employeeRequest(ownDeptId);
        employeeService.create(req); // must not throw

        verify(empRepo).save(any());
    }

    // -----------------------------------------------------------------------
    // Site 1b — Employee update: foreign-company departmentId rejected (PUT /api/v1/hr/employees/uid/{uid})
    // -----------------------------------------------------------------------

    @Test
    void updateEmployee_withForeignCompanyDepartment_throwsNotFound() {
        Employee emp = makeEmployee(COMPANY_A);
        when(empRepo.findByUid(EMP_UID)).thenReturn(Optional.of(emp));
        when(deptRepo.existsByIdAndCompanyId(FOREIGN_DEPT_ID, COMPANY_A)).thenReturn(false);

        CreateEmployeeRequest req = employeeRequest(FOREIGN_DEPT_ID);

        assertThatThrownBy(() -> employeeService.update(EMP_UID, req))
                .isInstanceOf(NotFoundException.class);

        // employee must not be mutated to reference the foreign department
        verify(empRepo, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // Site 2 — Leave request: foreign-company leaveTypeId rejected
    //          (POST /api/v1/hr/leave-requests/employee/{employeeUid})
    // -----------------------------------------------------------------------

    @Test
    void submitLeave_withForeignCompanyLeaveType_throwsNotFound() {
        Employee emp = makeEmployee(COMPANY_A);
        when(empRepo.findByUid(EMP_UID)).thenReturn(Optional.of(emp));
        // leave type 5 belongs to Company B — not found for Company A
        when(leaveTypeRepo.findByIdAndCompanyId(FOREIGN_LEAVE_TYPE_ID, COMPANY_A))
                .thenReturn(Optional.empty());

        SubmitLeaveRequest req = new SubmitLeaveRequest(FOREIGN_LEAVE_TYPE_ID,
                LocalDate.now(), LocalDate.now().plusDays(1), BigDecimal.ONE, "holiday");

        assertThatThrownBy(() -> leaveService.submit(EMP_UID, req))
                .isInstanceOf(NotFoundException.class);

        verify(leaveRequestRepo, never()).save(any());
    }

    @Test
    void submitLeave_withOwnCompanyLeaveType_succeeds() {
        Long ownLeaveTypeId = 2L;
        Employee emp = makeEmployee(COMPANY_A);
        when(empRepo.findByUid(EMP_UID)).thenReturn(Optional.of(emp));

        com.erp.modules.hr.domain.entity.LeaveType lt =
                new com.erp.modules.hr.domain.entity.LeaveType(
                        COMPANY_A, "AL", "Annual Leave", true,
                        BigDecimal.valueOf(21),
                        com.erp.modules.hr.domain.enums.LeaveAccrualMethod.ANNUAL_GRANT, 1L);
        when(leaveTypeRepo.findByIdAndCompanyId(ownLeaveTypeId, COMPANY_A))
                .thenReturn(Optional.of(lt));
        when(leaveRequestRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        SubmitLeaveRequest req = new SubmitLeaveRequest(ownLeaveTypeId,
                LocalDate.now(), LocalDate.now().plusDays(1), BigDecimal.ONE, null);

        leaveService.submit(EMP_UID, req); // must not throw

        verify(leaveRequestRepo).save(any());
    }

    // -----------------------------------------------------------------------
    // Site 3 — Pay component create: foreign-company glAccountId rejected
    //          (POST /api/v1/hr/pay-components)
    // -----------------------------------------------------------------------

    @Test
    void createPayComponent_withForeignCompanyGlAccount_throwsNotFound() {
        // code is unique within company A
        when(payComponentRepo.existsByCompanyIdAndCode(COMPANY_A, "BASIC")).thenReturn(false);
        // GL account 144 exists globally but belongs to Company B
        when(glAccountRepo.existsByIdAndCompanyId(FOREIGN_GL_ACCOUNT_ID, COMPANY_A)).thenReturn(false);

        CreatePayComponentRequest req = new CreatePayComponentRequest(
                "BASIC", "Basic Salary", PayComponentKind.EARNING,
                PayComponentBasis.FIXED, FOREIGN_GL_ACCOUNT_ID, true, true);

        assertThatThrownBy(() -> payComponentService.create(req))
                .isInstanceOf(NotFoundException.class);

        verify(payComponentRepo, never()).save(any());
    }

    // -----------------------------------------------------------------------
    // Site 4 — Employee loan create: foreign-company glAccountId rejected
    //          (POST /api/v1/hr/loans/employee/{employeeUid})
    // -----------------------------------------------------------------------

    @Test
    void createLoan_withForeignCompanyGlAccount_throwsNotFound() {
        Employee emp = makeEmployee(COMPANY_A);
        when(empRepo.findByUid(EMP_UID)).thenReturn(Optional.of(emp));
        // GL account 144 belongs to Company B — not found for Company A
        when(glAccountRepo.existsByIdAndCompanyId(FOREIGN_GL_ACCOUNT_ID, COMPANY_A)).thenReturn(false);

        CreateLoanRequest req = new CreateLoanRequest(
                EMP_ID,
                BigDecimal.valueOf(1_000_000),
                BigDecimal.valueOf(100_000),
                FOREIGN_GL_ACCOUNT_ID,
                LocalDate.now(),
                "TZS");

        assertThatThrownBy(() -> loanService.create(EMP_UID, req))
                .isInstanceOf(NotFoundException.class);

        verify(loanRepo, never()).save(any());
    }

    @Test
    void createLoan_withOwnCompanyGlAccount_succeeds() {
        Long ownGlAccountId = 10L;
        Employee emp = makeEmployee(COMPANY_A);
        when(empRepo.findByUid(EMP_UID)).thenReturn(Optional.of(emp));
        when(glAccountRepo.existsByIdAndCompanyId(ownGlAccountId, COMPANY_A)).thenReturn(true);
        when(numberGenerator.nextLoan(COMPANY_A)).thenReturn("LN-00001");
        when(loanRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateLoanRequest req = new CreateLoanRequest(
                EMP_ID,
                BigDecimal.valueOf(500_000),
                BigDecimal.valueOf(50_000),
                ownGlAccountId,
                LocalDate.now(),
                "TZS");

        loanService.create(EMP_UID, req); // must not throw

        verify(loanRepo).save(any());
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private Employee makeEmployee(Long companyId) {
        Employee emp = new Employee(companyId, 2L, "EMP-000001",
                "Jane", "Doe", LocalDate.of(2022, 1, 1), 1L);
        emp.setStatus(EmploymentStatus.ACTIVE);
        setId(emp, EMP_ID);
        return emp;
    }

    private CreateEmployeeRequest employeeRequest(Long departmentId) {
        return new CreateEmployeeRequest(
                "Jane", "Doe", null, null, null, null,
                LocalDate.of(1990, 1, 1), null,
                LocalDate.of(2022, 1, 1),
                departmentId, "Engineer", 2L, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null);
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
