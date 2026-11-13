package com.erp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.repository.UserRoleRepository;
import com.erp.modules.subscription.domain.enums.PlanCode;
import com.erp.modules.subscription.service.Entitlement;
import com.erp.modules.subscription.service.SubscriptionGate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-0069 D-4: the tenant's plan is applied on top of the user's role grants. */
@DisplayName("PermissionResolver applies the tenant's plan")
class PermissionResolverPlanTest {

    private static final long NOW = 1_000_000L;
    private static final Long ORG = 3L;
    private static final Long COMPANY = 100L;
    private static final Long BRANCH = 200L;

    private final UserRoleRepository userRoles = mock(UserRoleRepository.class);
    private final SubscriptionGate gate = mock(SubscriptionGate.class);
    private final CompanyTenantIndex tenants = mock(CompanyTenantIndex.class);
    private final PermissionResolver resolver = new PermissionResolver(userRoles, gate, tenants);

    @BeforeEach
    void setUp() {
        when(userRoles.resolvePermissionCodes(any(), any(), any()))
                .thenReturn(List.of("POS.SALE.CREATE", "GL.VIEW", "RESTAURANT.ORDER.MANAGE"));
        when(tenants.organisationOf(COMPANY)).thenReturn(ORG);
    }

    private void onPlan(Entitlement e) {
        when(gate.entitlementOf(ORG)).thenReturn(Optional.ofNullable(e));
        when(gate.permits(any(), anyString())).thenAnswer(inv ->
                e == null || e.permits(inv.getArgument(1)));
    }

    private static Entitlement basic(boolean restaurant) {
        return new Entitlement(PlanCode.BASIC, restaurant, false, null, null, null, null, null,
                null, 7, 7, ZoneId.of("Africa/Dar_es_Salaam"));
    }

    private static RequestContext.Principal user(boolean root) {
        return new RequestContext.Principal(9L, "u", root, COMPANY, BRANCH, null, ORG);
    }

    @Test
    void unboughtModulesDisappearFromTheResolvedSetAndAreRefused() {
        onPlan(basic(false));
        assertThat(resolver.resolve(9L, COMPANY, BRANCH, NOW)).containsExactly("POS.SALE.CREATE");
        assertThat(resolver.hasPermission(user(false), "POS.SALE.CREATE", NOW)).isTrue();
        assertThat(resolver.hasPermission(user(false), "GL.VIEW", NOW)).isFalse();
        assertThat(resolver.hasPermission(user(false), "RESTAURANT.ORDER.MANAGE", NOW)).isFalse();
    }

    @Test
    void anAddOnLightsUpItsCodes() {
        onPlan(basic(true));
        assertThat(resolver.resolve(9L, COMPANY, BRANCH, NOW))
                .containsExactlyInAnyOrder("POS.SALE.CREATE", "RESTAURANT.ORDER.MANAGE");
    }

    @Test
    void aTenantWithoutAPlanKeepsEverythingItWasGranted() {
        onPlan(null);
        assertThat(resolver.resolve(9L, COMPANY, BRANCH, NOW)).hasSize(3);
        assertThat(resolver.hasPermission(user(false), "GL.VIEW", NOW)).isTrue();
    }

    @Test
    void thePlatformOperatorIsNeverFiltered() {
        onPlan(basic(false));
        assertThat(resolver.hasPermission(user(true), "GL.VIEW", NOW)).isTrue();
    }

    @Test
    void aGrantNobodyHoldsIsStillRefusedWhateverThePlan() {
        onPlan(null);
        assertThat(resolver.hasPermission(user(false), "HR.PAYROLL.RUN", NOW)).isFalse();
    }
}
