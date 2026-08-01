package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.modules.stock.domain.dto.StockAvailabilityDto;
import com.erp.modules.stock.service.RecipeExplosionResolver;
import com.erp.modules.stock.service.StockReservationService;
import com.erp.platform.common.api.ConflictException;
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
 * Cross-layer contract: <b>what the Sales Settings screen reports for {@code allowNegativeStock}
 * must equal what {@link NegativeStockGuard} actually enforces</b>, for the SAME company state.
 *
 * <p><b>Why this test exists.</b> The setting shipped broken for a live client because each layer
 * was tested only in isolation, and the two suites asserted OPPOSITE things about the identical
 * "company has no {@code sales_settings} row" state — both green:
 * {@code SalesSettingsServiceImplTest} asserted the API reports "blocking", while
 * {@code NegativeStockGuardTest} asserted the guard did not guard at all. The screen said protected;
 * the till oversold. No unit test could catch that, because neither layer was wrong on its own — the
 * defect lived in the gap between them. This test lives in that gap: it drives the real
 * {@link SalesSettingsServiceImpl} and the real {@link NegativeStockGuard} off ONE shared repository
 * state and asserts they agree. Any future change to either default breaks it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NegativeStockSettingCrossLayerContractTest {

    @Mock private SalesSettingsRepository  settings;
    @Mock private CompanyRepository        companies;
    @Mock private ScopeGuard               scopeGuard;
    @Mock private StockReservationService  stock;
    @Mock private RecipeExplosionResolver  explosion;

    private static final String COMPANY_UID = "COUID00000000000000000001";
    private static final Long   COMPANY_ID  = 1L;
    private static final Long   BRANCH_ID   = 10L;
    private static final Long   PRODUCT_ID  = 100L;
    private static final String PRODUCT_UID = "PRODUID0000000000000000001";

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void noSettingsRow_theValueTheApiReports_isTheValueTheGuardEnforces() {
        givenSettingsRow(null);

        assertThat(guardAllowsBackorder())
                .as("a company with no Sales Settings row must be enforced exactly as the screen "
                        + "reports it — anything else means the screen is lying to the client")
                .isEqualTo(apiReportsBackorderAllowed());
        assertThat(apiReportsBackorderAllowed())
                .as("and that shared answer must be the fail-safe one: block")
                .isFalse();
    }

    @Test
    void savedRowBlocking_theValueTheApiReports_isTheValueTheGuardEnforces() {
        givenSettingsRow(false);

        assertThat(guardAllowsBackorder()).isEqualTo(apiReportsBackorderAllowed());
        assertThat(apiReportsBackorderAllowed()).isFalse();
    }

    @Test
    void savedRowAllowingBackorder_theValueTheApiReports_isTheValueTheGuardEnforces() {
        givenSettingsRow(true);

        assertThat(guardAllowsBackorder()).isEqualTo(apiReportsBackorderAllowed());
        assertThat(apiReportsBackorderAllowed()).isTrue();
    }

    // -------------------------------------------------------------------------

    /** {@code allowNegativeStock} as the Sales Settings API (and therefore the screen) reports it. */
    private boolean apiReportsBackorderAllowed() {
        return new SalesSettingsServiceImpl(settings, companies, scopeGuard)
                .getByCompanyUid(COMPANY_UID)
                .allowNegativeStock();
    }

    /** {@code allowNegativeStock} as the guard behaves: does a sale beyond on-hand get through? */
    private boolean guardAllowsBackorder() {
        NegativeStockGuard guard = new NegativeStockGuard(settings, stock, explosion);
        try {
            guard.assertAvailable(COMPANY_ID, BRANCH_ID, PRODUCT_ID, PRODUCT_UID, true,
                    "Widget", new BigDecimal("5"));
            return true;
        } catch (ConflictException blocked) {
            return false;
        }
    }

    /** {@code null} = no row at all; otherwise a saved row with that {@code allowNegativeStock}. */
    private void givenSettingsRow(Boolean allowNegativeStock) {
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));

        Optional<SalesSettings> row;
        if (allowNegativeStock == null) {
            row = Optional.empty();
        } else {
            SalesSettings s = new SalesSettings(COMPANY_ID, null);
            s.setAllowNegativeStock(allowNegativeStock);
            row = Optional.of(s);
        }
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(row);

        when(explosion.shouldExplodeAtIssue(PRODUCT_UID, true)).thenReturn(false);
        // Nothing on hand — so whether the sale gets through is decided purely by the setting.
        when(stock.getAvailability(COMPANY_ID, BRANCH_ID, PRODUCT_ID))
                .thenReturn(new StockAvailabilityDto(COMPANY_ID, BRANCH_ID, PRODUCT_ID,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    }
}
