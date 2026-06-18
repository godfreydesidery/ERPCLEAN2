package com.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.hr.domain.dto.CreateNextOfKinRequest;
import com.erp.modules.hr.domain.dto.EmployeeNextOfKinDto;
import com.erp.modules.hr.domain.dto.UpdateNextOfKinRequest;
import com.erp.modules.hr.domain.entity.Employee;
import com.erp.modules.hr.domain.entity.EmployeeNextOfKin;
import com.erp.modules.hr.repository.EmployeeNextOfKinRepository;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EmployeeNextOfKinServiceImpl} (ADR-0040 D-11) covering:
 * <ul>
 *   <li>Adding a primary NOK clears the previous primary (clear-then-set).</li>
 *   <li>Adding a non-primary NOK does NOT touch an existing primary.</li>
 *   <li>Updating to primary clears the previous primary; staying primary does not.</li>
 *   <li>Deactivating sets status=INACTIVE and clears isPrimary.</li>
 *   <li>A NOK belonging to another employee is rejected (404).</li>
 * </ul>
 */
class EmployeeNextOfKinServiceImplTest {

    private EmployeeRepository          employeeRepo;
    private EmployeeNextOfKinRepository nokRepo;
    private ScopeGuard                  scopeGuard;
    private AuditService                audit;
    private EmployeeNextOfKinServiceImpl service;

    private static final Long   COMPANY_ID    = 1L;
    private static final Long   EMPLOYEE_ID   = 10L;
    private static final String EMPLOYEE_UID  = "01EMPUID00000000000000TEST";

    @BeforeEach
    void setUp() {
        employeeRepo = mock(EmployeeRepository.class);
        nokRepo      = mock(EmployeeNextOfKinRepository.class);
        scopeGuard   = mock(ScopeGuard.class);
        audit        = mock(AuditService.class);
        service = new EmployeeNextOfKinServiceImpl(employeeRepo, nokRepo, scopeGuard, audit);

        Employee employee = new Employee(COMPANY_ID, 2L, "EMP-000001",
                "Jane", "Doe", LocalDate.of(2020, 1, 1), null);
        setId(employee, EMPLOYEE_ID);
        setUidViaProtected(employee, EMPLOYEE_UID);

        when(employeeRepo.findByUid(EMPLOYEE_UID)).thenReturn(Optional.of(employee));
        RequestContext.clear();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void add_nonPrimary_doesNotClearExistingPrimary() {
        CreateNextOfKinRequest req = new CreateNextOfKinRequest(
                "Alice", "Sister", "0700", "a@x.com", false);
        stubSave();

        service.add(EMPLOYEE_UID, req);

        verify(nokRepo, never()).findByEmployeeIdAndIsPrimaryTrue(any());
        verify(nokRepo).save(any(EmployeeNextOfKin.class));
    }

    @Test
    void add_primary_clearsPreviousPrimary() {
        CreateNextOfKinRequest req = new CreateNextOfKinRequest(
                "Bob", "Brother", "0711", "b@x.com", true);

        EmployeeNextOfKin existing = new EmployeeNextOfKin(COMPANY_ID, EMPLOYEE_ID, "Old", null);
        existing.setPrimary(true);
        when(nokRepo.findByEmployeeIdAndIsPrimaryTrue(EMPLOYEE_ID)).thenReturn(Optional.of(existing));
        stubSave();

        service.add(EMPLOYEE_UID, req);

        assertThat(existing.isPrimary()).isFalse();
        verify(nokRepo).save(any(EmployeeNextOfKin.class));
    }

    @Test
    void add_primary_noExistingPrimary_stillSucceeds() {
        CreateNextOfKinRequest req = new CreateNextOfKinRequest(
                "Carol", "Mother", "0722", "c@x.com", true);
        when(nokRepo.findByEmployeeIdAndIsPrimaryTrue(EMPLOYEE_ID)).thenReturn(Optional.empty());
        stubSave();

        EmployeeNextOfKinDto dto = service.add(EMPLOYEE_UID, req);

        assertThat(dto).isNotNull();
        verify(nokRepo).save(any(EmployeeNextOfKin.class));
    }

    @Test
    void list_returnsAllForEmployee() {
        EmployeeNextOfKin k1 = newKin(50L, "01NOKUID0000000000000TEST1", "Alice");
        EmployeeNextOfKin k2 = newKin(51L, "01NOKUID0000000000000TEST2", "Bob");
        when(nokRepo.findByEmployeeId(EMPLOYEE_ID)).thenReturn(List.of(k1, k2));

        List<EmployeeNextOfKinDto> result = service.list(EMPLOYEE_UID);

        assertThat(result).extracting(EmployeeNextOfKinDto::name).containsExactly("Alice", "Bob");
    }

    @Test
    void update_toPrimary_clearsPreviousPrimary() {
        String nokUid = "01NOKUID0000000000000TEST3";
        EmployeeNextOfKin kin = newKin(52L, nokUid, "Dave");
        kin.setPrimary(false);
        when(nokRepo.findByUid(nokUid)).thenReturn(Optional.of(kin));

        EmployeeNextOfKin existing = new EmployeeNextOfKin(COMPANY_ID, EMPLOYEE_ID, "Old", null);
        existing.setPrimary(true);
        when(nokRepo.findByEmployeeIdAndIsPrimaryTrue(EMPLOYEE_ID)).thenReturn(Optional.of(existing));

        UpdateNextOfKinRequest req = new UpdateNextOfKinRequest(
                "Dave", "Father", "0733", "d@x.com", true);

        service.update(EMPLOYEE_UID, nokUid, req);

        assertThat(existing.isPrimary()).isFalse();
        assertThat(kin.isPrimary()).isTrue();
        assertThat(kin.getRelationship()).isEqualTo("Father");
    }

    @Test
    void update_stayingPrimary_doesNotClear() {
        String nokUid = "01NOKUID0000000000000TEST4";
        EmployeeNextOfKin kin = newKin(53L, nokUid, "Eve");
        kin.setPrimary(true);
        when(nokRepo.findByUid(nokUid)).thenReturn(Optional.of(kin));

        UpdateNextOfKinRequest req = new UpdateNextOfKinRequest(
                "Eve", "Spouse", "0744", "e@x.com", true);

        service.update(EMPLOYEE_UID, nokUid, req);

        verify(nokRepo, never()).findByEmployeeIdAndIsPrimaryTrue(any());
        assertThat(kin.isPrimary()).isTrue();
    }

    @Test
    void deactivate_setsInactiveAndClearsPrimary() {
        String nokUid = "01NOKUID0000000000000TEST5";
        EmployeeNextOfKin kin = newKin(54L, nokUid, "Frank");
        kin.setPrimary(true);
        when(nokRepo.findByUid(nokUid)).thenReturn(Optional.of(kin));

        service.deactivate(EMPLOYEE_UID, nokUid);

        assertThat(kin.getStatus()).isEqualTo("INACTIVE");
        assertThat(kin.isPrimary()).isFalse();
    }

    @Test
    void requireKin_wrongEmployee_throwsNotFound() {
        String nokUid = "01NOKUID0000000000000TEST6";
        EmployeeNextOfKin kin = new EmployeeNextOfKin(COMPANY_ID, 999L, "Stranger", null);
        setId(kin, 55L);
        setUidViaProtected(kin, nokUid);
        when(nokRepo.findByUid(nokUid)).thenReturn(Optional.of(kin));

        UpdateNextOfKinRequest req = new UpdateNextOfKinRequest(
                "Stranger", null, null, null, false);

        assertThatThrownBy(() -> service.update(EMPLOYEE_UID, nokUid, req))
                .isInstanceOf(NotFoundException.class);
    }

    // --- helpers ---

    private EmployeeNextOfKin newKin(Long id, String uid, String name) {
        EmployeeNextOfKin kin = new EmployeeNextOfKin(COMPANY_ID, EMPLOYEE_ID, name, null);
        setId(kin, id);
        setUidViaProtected(kin, uid);
        return kin;
    }

    private EmployeeNextOfKin stubSave() {
        EmployeeNextOfKin saved = new EmployeeNextOfKin(COMPANY_ID, EMPLOYEE_ID, "Saved", null);
        setId(saved, 60L);
        setUidViaProtected(saved, "01SAVEDNOK0000000000000001");
        when(nokRepo.save(any(EmployeeNextOfKin.class))).thenReturn(saved);
        return saved;
    }

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
