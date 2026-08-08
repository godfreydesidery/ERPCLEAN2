package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.sales.domain.dto.SalesSettingsDto;
import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.domain.enums.DiscountApprovalAction;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Cross-layer contract: <b>what the Sales Settings screen reports for the discount policy must equal
 * what {@link DiscountAuthorisationGuard} actually enforces</b>, for the SAME company state (K7,
 * V95).
 *
 * <p><b>Why this test exists.</b> Its siblings {@link NegativeStockSettingCrossLayerContractTest}
 * and {@link BelowCostSettingCrossLayerContractTest} record how the negative-stock setting shipped
 * broken to a live client: each layer was unit-tested alone, and the two suites asserted OPPOSITE
 * things about the identical "company has no {@code sales_settings} row" state — both green. The
 * screen said protected; the till oversold. The discount policy is the same shape again (columns on
 * the very same row, read by an API on one side and a guard on the other), and it carries one extra
 * way to go wrong that the booleans did not: a NUMBER. A ceiling the screen shows as 10% but the
 * guard enforces as 0% would refuse every discount in the shop while the settings page looks
 * perfectly reasonable — so this test pins the value, not just the stance.
 *
 * <p>It drives the real {@link SalesSettingsServiceImpl}, the real {@link DiscountPolicyProvider}
 * and the real {@link DiscountAuthorisationGuard} off ONE shared repository state.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiscountPolicySettingCrossLayerContractTest {

    @Mock private SalesSettingsRepository settings;
    @Mock private CompanyRepository       companies;
    @Mock private ScopeGuard              scopeGuard;
    @Mock private AppUserRepository       users;
    @Mock private PermissionResolver      permissionResolver;
    @Mock private AuditService            audit;

    private static final String COMPANY_UID = "COUID00000000000000000001";
    private static final Long   COMPANY_ID  = 1L;
    private static final Long   BRANCH_ID   = 10L;
    private static final Long   CASHIER_ID  = 55L;
    private static final Long   INVOICE_ID  = 900L;
    private static final String INVOICE_UID = "INVUID000000000000000000001";

    /** The line every case discounts: 1 000 gross, 500 off — a flat 50%. */
    private static final BigDecimal LINE_GROSS      = new BigDecimal("1000");
    private static final BigDecimal HALF_PRICE_OFF  = new BigDecimal("500");

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // The stance
    // -------------------------------------------------------------------------

    @Test
    void noSettingsRow_theValueTheApiReports_isTheValueTheGuardEnforces() {
        givenNoSettingsRow();

        assertThat(guardAllowsAHalfPriceLine())
                .as("a company with no Sales Settings row must be enforced exactly as the screen "
                        + "reports it — anything else means the screen is lying to the client")
                .isEqualTo(apiReportsPolicyOff());
        assertThat(apiReportsPolicyOff())
                .as("and that shared answer must be OFF — the pre-K7 behaviour, so wiring the "
                        + "policy up cannot start refusing discounts nobody opted into")
                .isTrue();
    }

    @Test
    void aSeededRowUntouched_readsOffOnBothSides() {
        // What CompanyProvisioningService actually creates: a bare row on the entity's own defaults.
        givenSettingsRow(new SalesSettings(COMPANY_ID, null));

        assertThat(guardAllowsAHalfPriceLine()).isEqualTo(apiReportsPolicyOff());
        assertThat(apiReportsPolicyOff())
                .as("a freshly provisioned company must not arrive enforcing a ceiling")
                .isTrue();
    }

    @Test
    void savedRowBlock_theValueTheApiReports_isTheValueTheGuardEnforces() {
        givenPolicy(DiscountApprovalAction.BLOCK, "10");

        assertThat(guardAllowsAHalfPriceLine())
                .as("the screen says BLOCK at 10%, so a 50% line must actually be refused")
                .isEqualTo(apiReportsPolicyOff());
        assertThat(apiReportsPolicyOff()).isFalse();
    }

    @Test
    void savedRowApprove_withNoManagerNamed_theValueTheApiReports_isTheValueTheGuardEnforces() {
        givenPolicy(DiscountApprovalAction.APPROVE, "10");

        assertThat(guardAllowsAHalfPriceLine()).isEqualTo(apiReportsPolicyOff());
        assertThat(apiReportsPolicyOff()).isFalse();
    }

    @Test
    void everyActionTheApiCanReport_isAnActionTheGuardUnderstands() {
        // A stance the client can select on the screen but the guard silently ignores would be a
        // policy that can be chosen and never got. Walk the whole enum through both layers.
        for (DiscountApprovalAction action : DiscountApprovalAction.values()) {
            givenPolicy(action, "10");

            assertThat(apiReportsAction())
                    .as("the API must report back exactly the stored stance")
                    .isEqualTo(action);
            assertThat(guardAllowsAHalfPriceLine())
                    .as("guard behaviour for %s must match whether that stance lets a 50%% line "
                            + "through a 10%% ceiling", action)
                    .isEqualTo(action == DiscountApprovalAction.OFF
                            || action == DiscountApprovalAction.WARN);
        }
    }

    // -------------------------------------------------------------------------
    // The number — the failure mode the boolean settings could not have
    // -------------------------------------------------------------------------

    @Test
    void theCeilingTheApiReports_isTheCeilingTheGuardEnforces() {
        givenPolicy(DiscountApprovalAction.BLOCK, "7.50");

        assertThat(apiReportsCeiling())
                .as("the screen must show the stored ceiling to the decimal — NUMERIC(5,2)")
                .isEqualByComparingTo("7.50");
        // Enforced on the same number, from both directions.
        assertThat(guardAllows(new BigDecimal("75")))
                .as("7.5% of 1000 is exactly the ceiling, and the ceiling is allowed")
                .isTrue();
        assertThat(guardAllows(new BigDecimal("75.01")))
                .as("a hair over the ceiling the screen shows must be refused")
                .isFalse();
    }

    @Test
    void aPolicyOnWithNoCeiling_isReportedAsUnsetAndEnforcedAsZero() {
        // The one place the two layers legitimately say different WORDS for the same thing: the API
        // reports null ("nothing configured"), the guard reads it as 0 ("nothing may be given
        // without a manager"). That reading must be the strict one — reading an unset ceiling as
        // "unlimited" is precisely how the negative-stock toggle looked enabled while allowing
        // everything.
        givenPolicy(DiscountApprovalAction.BLOCK, null);

        assertThat(apiReportsCeiling()).isNull();
        assertThat(guardAllows(new BigDecimal("0.01")))
                .as("turning the policy on without naming a ceiling must DO something")
                .isFalse();
    }

    @Test
    void anOffPolicyIgnoresItsCeilingEntirely() {
        // A company that configured a ceiling and then switched the stance back to OFF is not
        // half-protected: OFF means no check, ceiling or not.
        givenPolicy(DiscountApprovalAction.OFF, "1");

        assertThat(apiReportsCeiling()).isEqualByComparingTo("1");
        assertThat(guardAllowsAHalfPriceLine()).isTrue();
    }

    // -------------------------------------------------------------------------
    // How each layer is asked
    // -------------------------------------------------------------------------

    private SalesSettingsDto apiReports() {
        return new SalesSettingsServiceImpl(settings, companies, scopeGuard)
                .getByCompanyUid(COMPANY_UID);
    }

    private DiscountApprovalAction apiReportsAction() {
        return apiReports().discountApprovalAction();
    }

    private BigDecimal apiReportsCeiling() {
        return apiReports().maxDiscountPercent();
    }

    /** {@code true} when the API says the discount policy does not refuse anything. */
    private boolean apiReportsPolicyOff() {
        return apiReportsAction() == DiscountApprovalAction.OFF;
    }

    /** How the guard behaves: does a 50%-off line get through? */
    private boolean guardAllowsAHalfPriceLine() {
        return guardAllows(HALF_PRICE_OFF);
    }

    /** How the guard behaves for a given absolute discount off a 1 000 line. No manager named. */
    private boolean guardAllows(BigDecimal discountAmount) {
        RequestContext.set(new RequestContext.Principal(
                CASHIER_ID, "cashier", false, COMPANY_ID, BRANCH_ID, "127.0.0.1"));
        DiscountAuthorisationGuard guard = new DiscountAuthorisationGuard(
                new DiscountPolicyProvider(settings), users, permissionResolver, audit);
        try {
            guard.authoriseLineDiscount(new DiscountAuthorisationGuard.DiscountRequest(
                    COMPANY_ID, INVOICE_ID, INVOICE_UID, "Sugar 1kg",
                    LINE_GROSS, discountAmount, null, null));
            return true;
        } catch (ConflictException refused) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // One shared repository state, read by both layers
    // -------------------------------------------------------------------------

    private void givenPolicy(DiscountApprovalAction action, String ceiling) {
        SalesSettings row = new SalesSettings(COMPANY_ID, null);
        row.setDiscountApprovalAction(action);
        row.setMaxDiscountPercent(ceiling == null ? null : new BigDecimal(ceiling));
        givenSettingsRow(row);
    }

    private void givenSettingsRow(SalesSettings row) {
        givenCompany();
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(row));
    }

    private void givenNoSettingsRow() {
        givenCompany();
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());
    }

    private void givenCompany() {
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));
        // No manager is ever named in this suite, so nobody holds anything.
        when(permissionResolver.hasPermission(any(), any(), anyLong())).thenReturn(false);
    }
}
