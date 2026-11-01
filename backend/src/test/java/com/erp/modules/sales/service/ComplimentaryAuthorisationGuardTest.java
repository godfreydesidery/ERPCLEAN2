package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.sales.domain.exception.PosSaleFlowException;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.TenancyScopeEnforcer;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ComplimentaryAuthorisationGuardTest {

    @Mock AppUserRepository users;
    @Mock PermissionResolver permissionResolver;
    @Mock AuditService audit;
    @Spy TenancyScopeEnforcer tenancy = new TenancyScopeEnforcer();

    @InjectMocks ComplimentaryAuthorisationGuard guard;

    private static final Long COMPANY_ID = 4242L;
    private static final Long CASHIER_ID = 55L;
    private static final String MANAGER_UID = "MGRUID00000000000000000001";
    private static final BigDecimal AMOUNT = new BigDecimal("24000");
    private static final Long ORG_ID = 9L;

    @BeforeEach
    void setCaller() {
        RequestContext.set(new RequestContext.Principal(
                CASHIER_ID, "cashier", false, COMPANY_ID, 1L, null, ORG_ID));
    }

    @AfterEach
    void clearCaller() {
        RequestContext.clear();
    }

    private AppUser user(Long id, String uid) {
        AppUser u = new AppUser("manager", "x", "Maria Manager");
        setField(u, "id", id);
        setField(u, "uid", uid);
        u.setOrganisationId(ORG_ID);
        return u;
    }

    private String code(Throwable t) {
        return ((PosSaleFlowException) t).getErrorCode();
    }

    @Test
    void aCashierWhoHoldsTheRight_needsNoManager() {
        when(permissionResolver.hasPermission(any(), eq("SALES.DISCOUNT.OVERRIDE"), anyLong()))
                .thenReturn(true);
        assertThatCode(() -> guard.authorise(COMPANY_ID, 1L, "INV", AMOUNT, null))
                .doesNotThrowAnyException();
        verify(audit).record(any());
    }

    @Test
    void withoutTheRightOrAManager_itIsRefused() {
        assertThatThrownBy(() -> guard.authorise(COMPANY_ID, 1L, "INV", AMOUNT, null))
                .isInstanceOf(PosSaleFlowException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("COMPLIMENTARY_APPROVAL_REQUIRED"));
        verify(audit, never()).record(any());
    }

    @Test
    void aManagerWhoHoldsTheRight_approves() {
        when(users.findByUid(MANAGER_UID)).thenReturn(Optional.of(user(77L, MANAGER_UID)));
        when(permissionResolver.hasPermission(any(), eq("SALES.DISCOUNT.OVERRIDE"), anyLong()))
                .thenAnswer(inv -> Long.valueOf(77L)
                        .equals(inv.<RequestContext.Principal>getArgument(0).userId()));
        assertThatCode(() -> guard.authorise(COMPANY_ID, 1L, "INV", AMOUNT, MANAGER_UID))
                .doesNotThrowAnyException();
        verify(audit).record(any());
    }

    @Test
    void aManagerWithoutTheRight_orTheCashierThemselves_isNotAccepted() {
        when(users.findByUid(MANAGER_UID)).thenReturn(Optional.of(user(77L, MANAGER_UID)));
        assertThatThrownBy(() -> guard.authorise(COMPANY_ID, 1L, "INV", AMOUNT, MANAGER_UID))
                .isInstanceOf(PosSaleFlowException.class);

        when(users.findByUid("SELF")).thenReturn(Optional.of(user(CASHIER_ID, "SELF")));
        assertThatThrownBy(() -> guard.authorise(COMPANY_ID, 1L, "INV", AMOUNT, "SELF"))
                .isInstanceOf(PosSaleFlowException.class);
        verify(audit, never()).record(any());
    }
}
