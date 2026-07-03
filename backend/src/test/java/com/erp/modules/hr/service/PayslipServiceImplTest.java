package com.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.hr.domain.dto.PayslipDto;
import com.erp.modules.hr.domain.entity.Employee;
import com.erp.modules.hr.domain.entity.PayrollRun;
import com.erp.modules.hr.domain.entity.Payslip;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.modules.hr.repository.PayrollRunRepository;
import com.erp.modules.hr.repository.PayslipRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link PayslipServiceImpl}: employee-name enrichment, ordering, and scope guard. */
class PayslipServiceImplTest {

    private PayslipRepository    payslips;
    private PayrollRunRepository runs;
    private EmployeeRepository   employees;
    private ScopeGuard           scopeGuard;

    private PayslipServiceImpl service;

    @BeforeEach
    void setUp() {
        payslips   = mock(PayslipRepository.class);
        runs       = mock(PayrollRunRepository.class);
        employees  = mock(EmployeeRepository.class);
        scopeGuard = mock(ScopeGuard.class);

        service = new PayslipServiceImpl(payslips, runs, employees, scopeGuard);

        RequestContext.set(new RequestContext.Principal(1L, "test@erp.com", false, 10L, null, null));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void listByPayrollRunUid_returnsEnrichedPayslipsOrderedByEmployeeName() {
        PayrollRun run = makeRun("RUN-1", 10L, 100L);
        when(runs.findByUid("RUN-1")).thenReturn(Optional.of(run));

        Payslip slip1 = makePayslip("SLIP-1", 10L, 100L, 1L); // employeeId 1 -> "Bob Zulu"
        Payslip slip2 = makePayslip("SLIP-2", 10L, 100L, 2L); // employeeId 2 -> "Alice Young"
        when(payslips.findByPayrollRunId(100L)).thenReturn(List.of(slip1, slip2));

        Employee bob = makeEmployee(1L, "Bob", "Zulu", "EMP-002");
        Employee alice = makeEmployee(2L, "Alice", "Young", "EMP-001");
        when(employees.findById(1L)).thenReturn(Optional.of(bob));
        when(employees.findById(2L)).thenReturn(Optional.of(alice));

        List<PayslipDto> result = service.listByPayrollRunUid("RUN-1");

        assertThat(result).hasSize(2);
        // Ordered by employee name: "Alice Young" before "Bob Zulu"
        assertThat(result.get(0).employeeName()).isEqualTo("Alice Young");
        assertThat(result.get(0).employeeNumber()).isEqualTo("EMP-001");
        assertThat(result.get(0).uid()).isEqualTo("SLIP-2");
        assertThat(result.get(1).employeeName()).isEqualTo("Bob Zulu");
        assertThat(result.get(1).employeeNumber()).isEqualTo("EMP-002");
        assertThat(result.get(1).uid()).isEqualTo("SLIP-1");

        verify(scopeGuard).assertCanActIn(any(), eq(10L));
    }

    @Test
    void listByPayrollRunUid_employeeMissing_leavesNameFieldsNull() {
        PayrollRun run = makeRun("RUN-2", 10L, 200L);
        when(runs.findByUid("RUN-2")).thenReturn(Optional.of(run));

        Payslip slip = makePayslip("SLIP-3", 10L, 200L, 99L);
        when(payslips.findByPayrollRunId(200L)).thenReturn(List.of(slip));
        when(employees.findById(99L)).thenReturn(Optional.empty());

        List<PayslipDto> result = service.listByPayrollRunUid("RUN-2");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).employeeName()).isNull();
        assertThat(result.get(0).employeeNumber()).isNull();
    }

    @Test
    void listByPayrollRunUid_crossCompanyRun_deniedByScopeGuard() {
        PayrollRun foreignRun = makeRun("RUN-X", 20L, 300L); // different company than caller's (10L)
        when(runs.findByUid("RUN-X")).thenReturn(Optional.of(foreignRun));
        doThrow(ForbiddenException.notPermitted())
                .when(scopeGuard).assertCanActIn(any(), eq(20L));

        assertThatThrownBy(() -> service.listByPayrollRunUid("RUN-X"))
                .isInstanceOf(ForbiddenException.class);

        verify(payslips, never()).findByPayrollRunId(any());
    }

    @Test
    void listByPayrollRunUid_unknownRunUid_throwsNotFound() {
        when(runs.findByUid("MISSING")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.listByPayrollRunUid("MISSING"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getByUid_returnsEnrichedPayslip() {
        Payslip slip = makePayslip("SLIP-9", 10L, 400L, 5L);
        when(payslips.findByUid("SLIP-9")).thenReturn(Optional.of(slip));
        when(employees.findById(5L)).thenReturn(Optional.of(makeEmployee(5L, "Carol", "King", "EMP-005")));

        PayslipDto dto = service.getByUid("SLIP-9");

        assertThat(dto.uid()).isEqualTo("SLIP-9");
        assertThat(dto.employeeName()).isEqualTo("Carol King");
        assertThat(dto.employeeNumber()).isEqualTo("EMP-005");
        verify(scopeGuard).assertCanActIn(any(), eq(10L));
    }

    @Test
    void getByUid_unknownUid_throwsNotFound() {
        when(payslips.findByUid("MISSING")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByUid("MISSING"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getByUid_crossCompanyPayslip_deniedByScopeGuard() {
        Payslip foreignSlip = makePayslip("SLIP-FOREIGN", 30L, 500L, 7L);
        when(payslips.findByUid("SLIP-FOREIGN")).thenReturn(Optional.of(foreignSlip));
        doThrow(ForbiddenException.notPermitted())
                .when(scopeGuard).assertCanActIn(any(), eq(30L));

        assertThatThrownBy(() -> service.getByUid("SLIP-FOREIGN"))
                .isInstanceOf(ForbiddenException.class);

        verify(employees, never()).findById(any());
    }

    // ---- helpers ----

    private PayrollRun makeRun(String uid, Long companyId, Long id) {
        PayrollRun run = new PayrollRun(companyId, null, "PR-0001",
                (short) 2024, (short) 6, LocalDate.of(2024, 6, 30), 1L);
        setField(run, "id", id);
        setUid(run, uid);
        return run;
    }

    private Payslip makePayslip(String uid, Long companyId, Long payrollRunId, Long employeeId) {
        Payslip slip = new Payslip(companyId, payrollRunId, 1L, employeeId,
                "PAYSLIP-00001", LocalDate.of(2024, 6, 30),
                new BigDecimal("1000000.0000"), new BigDecimal("200000.0000"),
                new BigDecimal("800000.0000"), new BigDecimal("50000.0000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1L);
        setUid(slip, uid);
        return slip;
    }

    private Employee makeEmployee(Long id, String firstName, String lastName, String employeeNumber) {
        Employee emp = new Employee(10L, null, employeeNumber, firstName, lastName,
                LocalDate.of(2020, 1, 1), 1L);
        setField(emp, "id", id);
        return emp;
    }

    private static void setUid(Object entity, String uid) {
        try {
            Method m = com.erp.platform.common.domain.UidEntity.class
                    .getDeclaredMethod("setUid", String.class);
            m.setAccessible(true);
            m.invoke(entity, uid);
        } catch (Exception e) {
            throw new RuntimeException("Could not set uid on " + entity.getClass(), e);
        }
    }

    private static void setField(Object target, String fieldName, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot set field " + fieldName, e);
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
