package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.products.service.LeafCostResolver;
import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.domain.enums.BelowCostAction;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Cross-layer contract: <b>what the Sales Settings screen reports for {@code belowCostAction} must
 * equal what {@link BelowCostGuard} actually enforces</b>, for the SAME company state.
 *
 * <p><b>Why this test exists.</b> Its sibling
 * {@link NegativeStockSettingCrossLayerContractTest} documents how the negative-stock setting
 * shipped broken to a live client: each layer was unit-tested in isolation, and the two suites
 * asserted OPPOSITE things about the identical "company has no {@code sales_settings} row" state —
 * both green. The screen said protected; the till oversold. The below-cost policy is the very same
 * shape (one column on the very same row, read by an API on one side and a guard on the other), so
 * it is exactly as easy to get wrong in exactly the same way. This test lives in the gap between the
 * layers: it drives the real {@link SalesSettingsServiceImpl} and the real {@link BelowCostGuard}
 * off ONE shared repository state and asserts they agree. Any future change to either default
 * breaks it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BelowCostSettingCrossLayerContractTest {

    @Mock private SalesSettingsRepository settings;
    @Mock private CompanyRepository       companies;
    @Mock private ScopeGuard              scopeGuard;
    @Mock private LeafCostResolver        costs;
    @Mock private PermissionResolver      permissionResolver;
    @Mock private AuditService            audit;

    private static final String COMPANY_UID = "COUID00000000000000000001";
    private static final Long   COMPANY_ID  = 1L;
    private static final Long   BRANCH_ID   = 10L;
    private static final Long   INVOICE_ID  = 900L;
    private static final String INVOICE_UID = "INVUID000000000000000000001";
    private static final Long   PRODUCT_ID  = 100L;

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void noSettingsRow_theValueTheApiReports_isTheValueTheGuardEnforces() {
        givenSettingsRow(null);

        assertThat(guardAllowsSaleAtCost())
                .as("a company with no Sales Settings row must be enforced exactly as the screen "
                        + "reports it — anything else means the screen is lying to the client")
                .isEqualTo(apiReportsPolicyOff());
        assertThat(apiReportsPolicyOff())
                .as("and that shared answer must be OFF — the pre-V93 behaviour, so upgrading "
                        + "cannot start rejecting sales nobody opted into")
                .isTrue();
    }

    @Test
    void savedRowOff_theValueTheApiReports_isTheValueTheGuardEnforces() {
        givenSettingsRow(BelowCostAction.OFF);

        assertThat(guardAllowsSaleAtCost()).isEqualTo(apiReportsPolicyOff());
        assertThat(apiReportsPolicyOff()).isTrue();
    }

    @Test
    void savedRowBlock_theValueTheApiReports_isTheValueTheGuardEnforces() {
        givenSettingsRow(BelowCostAction.BLOCK);

        assertThat(guardAllowsSaleAtCost())
                .as("the screen says BLOCK, so a sale at cost must actually be rejected")
                .isEqualTo(apiReportsPolicyOff());
        assertThat(apiReportsPolicyOff()).isFalse();
    }

    @Test
    void savedRowApprove_theValueTheApiReports_isTheValueTheGuardEnforces() {
        givenSettingsRow(BelowCostAction.APPROVE);
        // No override supplied and none held — APPROVE must behave as a rejection here.
        when(permissionResolver.hasPermission(any(), any(), anyLong())).thenReturn(false);

        assertThat(guardAllowsSaleAtCost()).isEqualTo(apiReportsPolicyOff());
        assertThat(apiReportsPolicyOff()).isFalse();
    }

    @Test
    void everyActionTheApiCanReport_isAnActionTheGuardUnderstands() {
        // A value the API can hand to the screen but the guard silently ignores would be a policy
        // the client can select and never get. Walk the whole enum through both layers.
        for (BelowCostAction action : BelowCostAction.values()) {
            givenSettingsRow(action);
            when(permissionResolver.hasPermission(any(), any(), anyLong())).thenReturn(false);

            assertThat(apiReportsAction())
                    .as("the API must report back exactly the stored action")
                    .isEqualTo(action);
            assertThat(guardAllowsSaleAtCost())
                    .as("guard behaviour for %s must match whether that action is a no-op", action)
                    .isEqualTo(action == BelowCostAction.OFF || action == BelowCostAction.WARN);
        }
    }

    // -------------------------------------------------------------------------

    /** The action as the Sales Settings API (and therefore the screen) reports it. */
    private BelowCostAction apiReportsAction() {
        return new SalesSettingsServiceImpl(settings, companies, scopeGuard)
                .getByCompanyUid(COMPANY_UID)
                .belowCostAction();
    }

    /** {@code true} when the API says the below-cost policy does not reject anything. */
    private boolean apiReportsPolicyOff() {
        return apiReportsAction() == BelowCostAction.OFF;
    }

    /** How the guard behaves: does a line priced exactly AT cost get through? */
    private boolean guardAllowsSaleAtCost() {
        BelowCostGuard guard = new BelowCostGuard(settings, costs, permissionResolver, audit);
        try {
            guard.assertNotBelowCost(COMPANY_ID, BRANCH_ID, INVOICE_ID, INVOICE_UID,
                    List.of(new BelowCostGuard.PricedLine(
                            PRODUCT_ID, "Widget", new BigDecimal("1000"), BigDecimal.ONE)),
                    false);
            return true;
        } catch (ConflictException rejected) {
            return false;
        }
    }

    /** {@code null} = no row at all; otherwise a saved row carrying that action. */
    private void givenSettingsRow(BelowCostAction action) {
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));

        Optional<SalesSettings> row;
        if (action == null) {
            row = Optional.empty();
        } else {
            SalesSettings s = new SalesSettings(COMPANY_ID, null);
            s.setBelowCostAction(action);
            row = Optional.of(s);
        }
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(row);

        // Cost exactly equals the line's net unit price — so whether the sale gets through is
        // decided purely by the policy, not by the numbers.
        when(costs.avgCosts(any(), any(), any()))
                .thenReturn(Map.of(PRODUCT_ID, new BigDecimal("1000")));
    }
}
