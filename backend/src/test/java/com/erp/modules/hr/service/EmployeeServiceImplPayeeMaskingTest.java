package com.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.hr.domain.dto.EmployeeDto;
import com.erp.modules.hr.domain.entity.Employee;
import com.erp.modules.hr.domain.enums.PaymentMethod;
import com.erp.modules.hr.repository.DepartmentRepository;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for sensitive-payee field masking in {@link EmployeeServiceImpl#getByUid} (ADR-0040 D-11).
 *
 * <p>The bank account number, account name and mobile-money number are returned only when the
 * caller holds {@code HR.EMPLOYEE.PAYEE.VIEW}; the non-sensitive context (method, bank name, branch)
 * is always returned.
 */
class EmployeeServiceImplPayeeMaskingTest {

    private static final String PAYEE_VIEW   = "HR.EMPLOYEE.PAYEE.VIEW";
    private static final Long   COMPANY_ID   = 1L;
    private static final Long   EMPLOYEE_ID  = 10L;
    private static final String EMPLOYEE_UID = "01EMPUID00000000000000TEST";

    private EmployeeRepository   employeeRepo;
    private DepartmentRepository departmentRepo;
    private BranchRepository     branchRepo;
    private HrNumberGenerator    numberGenerator;
    private ScopeGuard           scopeGuard;
    private AuditService         audit;
    private PermissionResolver   permissions;
    private EmployeeServiceImpl  service;

    @BeforeEach
    void setUp() {
        employeeRepo    = mock(EmployeeRepository.class);
        departmentRepo  = mock(DepartmentRepository.class);
        branchRepo      = mock(BranchRepository.class);
        numberGenerator = mock(HrNumberGenerator.class);
        scopeGuard      = mock(ScopeGuard.class);
        audit           = mock(AuditService.class);
        permissions     = mock(PermissionResolver.class);
        service = new EmployeeServiceImpl(employeeRepo, departmentRepo, branchRepo,
                numberGenerator, scopeGuard, audit, permissions);

        Employee emp = new Employee(COMPANY_ID, 2L, "EMP-000001",
                "Jane", "Doe", LocalDate.of(2020, 1, 1), null);
        setId(emp, EMPLOYEE_ID);
        setUidViaProtected(emp, EMPLOYEE_UID);
        emp.setPaymentMethod(PaymentMethod.BANK_TRANSFER);
        emp.setBankName("CRDB");
        emp.setBankBranch("Main");
        emp.setBankAccountNo("0150123456789");
        emp.setBankAccountName("Jane Doe");
        emp.setMobileMoneyNo("0700123456");

        when(employeeRepo.findByUid(EMPLOYEE_UID)).thenReturn(Optional.of(emp));
        RequestContext.set(new RequestContext.Principal(
                99L, "alice", false, COMPANY_ID, 2L, "127.0.0.1"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void getByUid_withPayeeView_returnsSensitiveFields() {
        when(permissions.hasPermission(any(), eq(PAYEE_VIEW), anyLong())).thenReturn(true);

        EmployeeDto dto = service.getByUid(EMPLOYEE_UID);

        assertThat(dto.bankAccountNo()).isEqualTo("0150123456789");
        assertThat(dto.bankAccountName()).isEqualTo("Jane Doe");
        assertThat(dto.mobileMoneyNo()).isEqualTo("0700123456");
        // non-sensitive context still present
        assertThat(dto.bankName()).isEqualTo("CRDB");
        assertThat(dto.bankBranch()).isEqualTo("Main");
        assertThat(dto.paymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
    }

    @Test
    void getByUid_withoutPayeeView_masksSensitiveFields() {
        when(permissions.hasPermission(any(), eq(PAYEE_VIEW), anyLong())).thenReturn(false);

        EmployeeDto dto = service.getByUid(EMPLOYEE_UID);

        assertThat(dto.bankAccountNo()).isNull();
        assertThat(dto.bankAccountName()).isNull();
        assertThat(dto.mobileMoneyNo()).isNull();
        // non-sensitive context still present
        assertThat(dto.bankName()).isEqualTo("CRDB");
        assertThat(dto.bankBranch()).isEqualTo("Main");
        assertThat(dto.paymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
    }

    // --- helpers ---

    private static void setId(Object entity, Long id) {
        try {
            java.lang.reflect.Field f = com.erp.platform.common.domain.UidEntity.class
                    .getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setUidViaProtected(com.erp.platform.common.domain.UidEntity entity, String uid) {
        try {
            java.lang.reflect.Method m = com.erp.platform.common.domain.UidEntity.class
                    .getDeclaredMethod("setUid", String.class);
            m.setAccessible(true);
            m.invoke(entity, uid);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
