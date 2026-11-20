package com.erp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.subscription.domain.enums.PlanCode;
import com.erp.modules.subscription.domain.enums.SubscriptionStage;
import com.erp.modules.subscription.service.Entitlement;
import com.erp.modules.subscription.service.SubscriptionGate;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("SubscriptionAccessGuard (ADR-0069 D-6, D-9)")
class SubscriptionAccessGuardTest {

    private static final long ORG = 7L;
    private final SubscriptionGate gate = mock(SubscriptionGate.class);
    private final SubscriptionAccessGuard guard = new SubscriptionAccessGuard(provider(gate));

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    @Test
    void readOnlyRefusesWritesIncludingThePos() {
        tenantUser(SubscriptionStage.READ_ONLY);
        assertThatThrownBy(() -> pass("POST", "/api/v1/pos/sales"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> pass("PUT", "/api/v1/products/uid/X"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void readOnlyStillAllowsReadsSessionReprintsAndOfflineSync() {
        tenantUser(SubscriptionStage.READ_ONLY);
        assertThat(pass("GET", "/api/v1/reports/sales")).isTrue();
        assertThat(pass("POST", "/api/v1/auth/logout")).isTrue();
        assertThat(pass("POST", "/api/v1/documents/render")).isTrue();
        assertThat(pass("POST", "/api/v1/pos/offline-sync")).isTrue();
    }

    @Test
    void suspendedRefusesEvenReadsButNotTheSessionCalls() {
        tenantUser(SubscriptionStage.SUSPENDED);
        assertThatThrownBy(() -> pass("GET", "/api/v1/reports/sales"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("suspended");
        assertThat(pass("GET", "/api/v1/auth/me")).isTrue();
    }

    @Test
    void graceAndActiveAreUntouched() {
        tenantUser(SubscriptionStage.GRACE);
        assertThat(pass("POST", "/api/v1/pos/sales")).isTrue();
    }

    @Test
    void thePlatformOperatorIsNeverBlocked() {
        when(gate.stageOf(any())).thenReturn(SubscriptionStage.SUSPENDED);
        RequestContext.set(new RequestContext.Principal(1L, "root", true, 10L, 20L, null, ORG));
        assertThat(pass("POST", "/api/v1/pos/sales")).isTrue();
    }

    @Test
    void orbixHqIsRefusedWithoutTheAddOnAndOnlyWhenTheAppSaysItIsOrbixHq() {
        tenantUser(SubscriptionStage.ACTIVE);
        when(gate.entitlementOf(ORG)).thenReturn(Optional.of(entitlement(false)));
        MockHttpServletRequest fromApp = request("GET", "/api/v1/reports/sales");
        fromApp.addHeader("X-Client-App", "orbixhq");
        assertThatThrownBy(() -> guard.preHandle(fromApp, new MockHttpServletResponse(), null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("OrbixHQ");
        assertThat(pass("GET", "/api/v1/reports/sales")).isTrue();

        when(gate.entitlementOf(ORG)).thenReturn(Optional.of(entitlement(true)));
        assertThat(guard.preHandle(fromApp, new MockHttpServletResponse(), null)).isTrue();
    }

    @Test
    void aTenantWithoutAPlanIsUntouched() {
        tenantUser(SubscriptionStage.UNRESTRICTED);
        MockHttpServletRequest fromApp = request("POST", "/api/v1/pos/sales");
        fromApp.addHeader("X-Client-App", "orbixhq");
        when(gate.entitlementOf(ORG)).thenReturn(Optional.empty());
        assertThat(guard.preHandle(fromApp, new MockHttpServletResponse(), null)).isTrue();
    }

    // -------------------------------------------------------------------------

    private void tenantUser(SubscriptionStage stage) {
        when(gate.stageOf(ORG)).thenReturn(stage);
        RequestContext.set(new RequestContext.Principal(5L, "asha@duka", false, 10L, 20L, null, ORG));
    }

    private boolean pass(String method, String path) {
        return guard.preHandle(request(method, path), new MockHttpServletResponse(), null);
    }

    private static MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    private static Entitlement entitlement(boolean orbixHq) {
        return new Entitlement(PlanCode.BASIC, false, orbixHq, null, null, null, null, null, null,
                7, 7, ZoneId.of("Africa/Dar_es_Salaam"));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SubscriptionGate> provider(SubscriptionGate gate) {
        ObjectProvider<SubscriptionGate> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(gate);
        return p;
    }
}
