package com.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.hr.domain.entity.EmploymentContract;
import com.erp.modules.hr.domain.enums.ContractType;
import com.erp.modules.hr.repository.EmployeeRepository;
import com.erp.modules.hr.repository.EmploymentContractRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ContractServiceImpl} covering D4: reject re-termination of an
 * already-terminated (inactive) contract.
 */
class ContractServiceImplTest {

    private EmploymentContractRepository contracts;
    private EmployeeRepository           employees;
    private ScopeGuard                   scopeGuard;
    private AuditService                 audit;
    private ContractServiceImpl          service;

    private static final Long   COMPANY_ID   = 10L;
    private static final String CONTRACT_UID = "01CTRUIDTEST000000000001";

    @BeforeEach
    void setUp() {
        contracts  = mock(EmploymentContractRepository.class);
        employees  = mock(EmployeeRepository.class);
        scopeGuard = mock(ScopeGuard.class);
        audit      = mock(AuditService.class);
        service = new ContractServiceImpl(contracts, employees, scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(99L, "hr@erp.com", false, COMPANY_ID, null, null));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // D4 — re-terminating an already-terminated contract is rejected (409)
    // -----------------------------------------------------------------------

    @Test
    void terminate_alreadyInactiveContract_throwsConflict() {
        EmploymentContract c = makeContract(false); // active=false → already terminated
        when(contracts.findByUid(CONTRACT_UID)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.terminate(CONTRACT_UID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already terminated");
    }

    @Test
    void terminate_activeContract_succeeds() {
        EmploymentContract c = makeContract(true); // active=true → can be terminated
        when(contracts.findByUid(CONTRACT_UID)).thenReturn(Optional.of(c));

        service.terminate(CONTRACT_UID); // must not throw

        org.assertj.core.api.Assertions.assertThat(c.isActive()).isFalse();
    }

    // -----------------------------------------------------------------------
    // helper
    // -----------------------------------------------------------------------

    private EmploymentContract makeContract(boolean active) {
        EmploymentContract c = new EmploymentContract(
                COMPANY_ID, 1L, ContractType.PERMANENT,
                BigDecimal.valueOf(500_000), "TZS",
                LocalDate.of(2023, 1, 1), null, 1L);
        setField(c, "active", active);
        setField(c, "id", 7L);
        setUid(c, CONTRACT_UID);
        return c;
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
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }

    private static void setUid(Object target, String uid) {
        try {
            Method m = com.erp.platform.common.domain.UidEntity.class
                    .getDeclaredMethod("setUid", String.class);
            m.setAccessible(true);
            m.invoke(target, uid);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
