package com.erp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.platform.common.api.ForbiddenException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link AuthorityCeiling} invariant (ADR-0059). The caller's effective permission
 * set is provided by a mocked {@link PermissionResolver}; the tests exercise the subset rule, the
 * reserved-permission floor, the root exemption, the fail-closed defaults and the system-role block.
 */
class AuthorityCeilingTest {

    private final PermissionResolver resolver = mock(PermissionResolver.class);
    private final AuthorityCeiling ceiling = new AuthorityCeiling(resolver);

    private static RequestContext.Principal nonRoot() {
        return new RequestContext.Principal(1L, "alice", false, 10L, 100L, null);
    }

    private static RequestContext.Principal root() {
        return new RequestContext.Principal(2L, "root", true, null, null, null);
    }

    private void callerHolds(String... codes) {
        when(resolver.resolve(anyLong(), anyLong(), any(), anyLong())).thenReturn(Set.of(codes));
    }

    // ---- subset rule -------------------------------------------------------

    @Test
    void confer_subsetOfCallerPermissions_passes() {
        callerHolds("SALES.INVOICE.CREATE", "SALES.INVOICE.VIEW", "STOCK.VIEW");
        assertThatCode(() -> ceiling.assertCanConfer(nonRoot(),
                List.of("SALES.INVOICE.CREATE", "STOCK.VIEW")))
                .doesNotThrowAnyException();
    }

    @Test
    void confer_codeCallerDoesNotHold_throwsForbidden() {
        callerHolds("SALES.INVOICE.CREATE");
        assertThatThrownBy(() -> ceiling.assertCanConfer(nonRoot(),
                List.of("SALES.INVOICE.CREATE", "GL.POST")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void confer_emptyCodes_alwaysAllowed_withoutResolving() {
        assertThatCode(() -> ceiling.assertCanConfer(nonRoot(), List.of()))
                .doesNotThrowAnyException();
        verify(resolver, never()).resolve(anyLong(), anyLong(), any(), anyLong());
    }

    // ---- root exemption / fail-closed --------------------------------------

    @Test
    void confer_rootCaller_isExempt_evenForReserved() {
        assertThatCode(() -> ceiling.assertCanConfer(root(), List.of("ROLE.MANAGE", "USER.MANAGE")))
                .doesNotThrowAnyException();
        verify(resolver, never()).resolve(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void confer_nullPrincipal_failsClosed() {
        assertThatThrownBy(() -> ceiling.assertCanConfer(null, List.of("STOCK.VIEW")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void confer_callerWithNoResolvedPermissions_canConferNothing() {
        callerHolds(); // empty effective set
        assertThatThrownBy(() -> ceiling.assertCanConfer(nonRoot(), List.of("STOCK.VIEW")))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- reserved-permission floor -----------------------------------------

    @Test
    void confer_reservedCode_callerNotOrgAdminTier_throwsForbidden() {
        // Caller holds ROLE.MANAGE (so the subset check passes) but NOT every reserved code, so the
        // reserved floor blocks passing the power-to-delegate onward.
        callerHolds("ROLE.MANAGE", "SALES.INVOICE.CREATE");
        assertThatThrownBy(() -> ceiling.assertCanConfer(nonRoot(), List.of("ROLE.MANAGE")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void confer_reservedCode_callerHoldsAllReserved_passes() {
        callerHolds("USER.MANAGE", "USER.COMPANY.MANAGE", "ROLE.MANAGE", "ROLE.ADMIN", "BRANCH.ASSIGN");
        assertThatCode(() -> ceiling.assertCanConfer(nonRoot(), List.of("ROLE.MANAGE", "USER.MANAGE")))
                .doesNotThrowAnyException();
    }

    // ---- role conferral (system-role block) --------------------------------

    @Test
    void conferRole_systemRole_nonRootCaller_throwsForbidden() {
        // A system role (e.g. ORG_ADMIN) is refused outright for a non-root caller — resolver not consulted.
        assertThatThrownBy(() -> ceiling.assertCanConferRole(nonRoot(), true, List.of("STOCK.VIEW")))
                .isInstanceOf(ForbiddenException.class);
        verify(resolver, never()).resolve(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void conferRole_systemRole_rootCaller_passes() {
        assertThatCode(() -> ceiling.assertCanConferRole(root(), true, List.of("STOCK.VIEW")))
                .doesNotThrowAnyException();
    }

    @Test
    void conferRole_nonSystemRole_delegatesToSubsetCheck() {
        callerHolds("STOCK.VIEW");
        assertThatCode(() -> ceiling.assertCanConferRole(nonRoot(), false, List.of("STOCK.VIEW")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ceiling.assertCanConferRole(nonRoot(), false, List.of("GL.POST")))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- isPrivileged helper ----------------------------------------------

    @Test
    void isPrivileged_detectsReservedCodes() {
        assertThat(AuthorityCeiling.isPrivileged(List.of("STOCK.VIEW", "SALES.INVOICE.CREATE"))).isFalse();
        assertThat(AuthorityCeiling.isPrivileged(List.of("STOCK.VIEW", "ROLE.MANAGE"))).isTrue();
        assertThat(AuthorityCeiling.isPrivileged(null)).isFalse();
        assertThat(AuthorityCeiling.isPrivileged(List.of())).isFalse();
    }
}
