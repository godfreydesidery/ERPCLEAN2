package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.sales.domain.dto.SalesSettingsDto;
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;
import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.domain.enums.DiscountApprovalAction;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link SalesSettingsServiceImpl} (deferred item D-4 settings CRUD). Mirrors the
 * intent of a PurchaseSettingsServiceImpl test: upsert creates the single per-company row on first
 * write, then updates that same row on a subsequent write.
 *
 * <p>Note on {@code allowNegativeStock}: what this suite asserts the API <em>reports</em> for a
 * company with no row must equal what {@code NegativeStockGuard} <em>enforces</em> for that same
 * company. That agreement is pinned by {@link NegativeStockSettingCrossLayerContractTest} — the two
 * once disagreed while both suites were green.
 */
@ExtendWith(MockitoExtension.class)
class SalesSettingsServiceImplTest {

    @Mock SalesSettingsRepository settings;
    @Mock CompanyRepository companies;
    @Mock ScopeGuard scopeGuard;

    @InjectMocks SalesSettingsServiceImpl service;

    private static final String COMPANY_UID = "COUID00000000000000000001";
    private static final Long   COMPANY_ID  = 1L;

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void getByCompanyUid_noRowYet_returnsDisabledDefaults() {
        Company company = company(COMPANY_ID);
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());

        SalesSettingsDto dto = service.getByCompanyUid(COMPANY_UID);

        assertThat(dto.soApprovalEnabled()).isFalse();
        assertThat(dto.soApprovalThresholdAmount()).isNull();
        assertThat(dto.currency()).isEqualTo("TZS");
        assertThat(dto.allowNegativeStock())
                .as("default is blocking (backorder off) until a company opts in")
                .isFalse();
        // Never persisted just by reading.
        org.mockito.Mockito.verify(settings, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void update_noRowYet_createsThenSetsFields() {
        Company company = company(COMPANY_ID);
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());
        when(settings.save(org.mockito.ArgumentMatchers.any(SalesSettings.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UpdateSalesSettingsRequest req = new UpdateSalesSettingsRequest(
                COMPANY_UID, true, BigDecimal.valueOf(2_000_000), "TZS", true);

        SalesSettingsDto dto = service.update(req);

        assertThat(dto.soApprovalEnabled()).isTrue();
        assertThat(dto.soApprovalThresholdAmount()).isEqualByComparingTo(BigDecimal.valueOf(2_000_000));
        assertThat(dto.currency()).isEqualTo("TZS");
        assertThat(dto.allowNegativeStock()).isTrue();
        // create (default row) + final save == 2 calls to save
        verify(settings, org.mockito.Mockito.times(2)).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void update_existingRow_updatesInPlace_doesNotCreateASecondRow() {
        SalesSettings existing = new SalesSettings(COMPANY_ID, 1L);
        existing.setSoApprovalEnabled(false);
        Company company = company(COMPANY_ID);
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(existing));
        when(settings.save(org.mockito.ArgumentMatchers.any(SalesSettings.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UpdateSalesSettingsRequest req = new UpdateSalesSettingsRequest(
                COMPANY_UID, true, BigDecimal.valueOf(500_000), "USD", false);

        SalesSettingsDto dto = service.update(req);

        assertThat(dto.soApprovalEnabled()).isTrue();
        assertThat(dto.soApprovalThresholdAmount()).isEqualByComparingTo(BigDecimal.valueOf(500_000));
        assertThat(dto.currency()).isEqualTo("USD");
        assertThat(dto.allowNegativeStock()).isFalse();
        ArgumentCaptor<SalesSettings> captor = ArgumentCaptor.forClass(SalesSettings.class);
        verify(settings, org.mockito.Mockito.times(1)).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing);
    }

    // ── Discount policy (K7, V95) ────────────────────────────────────────────
    //
    // The whole point of the pair is that it is CONFIGURABLE and that the screen's answer equals the
    // guard's. The agreement itself is pinned by DiscountPolicySettingCrossLayerContractTest; these
    // cases cover what the update endpoint does with the two fields.

    @Test
    void getByCompanyUid_noRowYet_reportsTheDiscountPolicyOff() {
        Company company = company(COMPANY_ID);
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());

        SalesSettingsDto dto = service.getByCompanyUid(COMPANY_UID);

        assertThat(dto.discountApprovalAction())
                .as("default is no ceiling at all until a company opts in — the pre-K7 behaviour")
                .isEqualTo(DiscountApprovalAction.OFF);
        assertThat(dto.maxDiscountPercent()).isNull();
    }

    @Test
    void update_setsTheDiscountPolicyAndItsCeiling() {
        SalesSettings existing = existingRow();

        SalesSettingsDto dto = service.update(new UpdateSalesSettingsRequest(
                COMPANY_UID, false, null, "TZS", false, null,
                DiscountApprovalAction.APPROVE, new BigDecimal("7.50")));

        assertThat(dto.discountApprovalAction()).isEqualTo(DiscountApprovalAction.APPROVE);
        assertThat(dto.maxDiscountPercent()).isEqualByComparingTo("7.50");
        assertThat(existing.getDiscountApprovalAction()).isEqualTo(DiscountApprovalAction.APPROVE);
        assertThat(existing.getMaxDiscountPercent()).isEqualByComparingTo("7.50");
    }

    @Test
    void update_withoutTheStance_leavesAConfiguredPolicyAlone() {
        // A caller that predates K7 — the old Sales Settings screen, an integration — must not be
        // able to switch a configured control off just by not knowing about it. That is exactly how
        // the negative-stock toggle ended up reading ON while never having been saved.
        SalesSettings existing = existingRow();
        existing.setDiscountApprovalAction(DiscountApprovalAction.BLOCK);
        existing.setMaxDiscountPercent(new BigDecimal("5.00"));

        SalesSettingsDto dto = service.update(new UpdateSalesSettingsRequest(
                COMPANY_UID, false, null, "TZS", true));

        assertThat(dto.discountApprovalAction()).isEqualTo(DiscountApprovalAction.BLOCK);
        assertThat(dto.maxDiscountPercent()).isEqualByComparingTo("5.00");
    }

    @Test
    void update_withAStanceAndNoCeiling_clearsTheCeiling() {
        // Sending the stance with a null ceiling is a real instruction ("no ceiling configured",
        // which the guard reads as zero), not an omission — otherwise a ceiling could be set once
        // and never removed.
        SalesSettings existing = existingRow();
        existing.setDiscountApprovalAction(DiscountApprovalAction.APPROVE);
        existing.setMaxDiscountPercent(new BigDecimal("10.00"));

        SalesSettingsDto dto = service.update(new UpdateSalesSettingsRequest(
                COMPANY_UID, false, null, "TZS", false, null,
                DiscountApprovalAction.APPROVE, null));

        assertThat(dto.maxDiscountPercent()).isNull();
        assertThat(existing.getMaxDiscountPercent()).isNull();
    }

    @Test
    void update_withACeilingButNoStance_isRejected_notSilentlyAccepted() {
        // UAT finding #3. Sending only maxDiscountPercent used to answer 200 OK, save nothing, and
        // hand back the OLD ceiling — so an administrator read "saved" and the shop then ran on a
        // limit nobody set. Applying the pair atomically is right; answering "done" is not.
        //
        // This cannot break a client that predates K7: those send NEITHER field, which still means
        // "leave the policy alone" (update_withoutTheStance_leavesAConfiguredPolicyAlone, above).
        Company company = company(COMPANY_ID);
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));

        UpdateSalesSettingsRequest ceilingOnly = new UpdateSalesSettingsRequest(
                COMPANY_UID, false, null, "TZS", false, null, null, new BigDecimal("25.00"));

        assertThatThrownBy(() -> service.update(ceilingOnly))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDiscountPercent")
                .hasMessageContaining("discount approval action");

        // Refused before anything is read or written — so a company with no row yet does not get
        // one created as a side effect of a request that was never going to be applied.
        verify(settings, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
        verify(settings, org.mockito.Mockito.never()).findByCompanyId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void update_canSwitchTheDiscountPolicyBackOff() {
        SalesSettings existing = existingRow();
        existing.setDiscountApprovalAction(DiscountApprovalAction.BLOCK);
        existing.setMaxDiscountPercent(new BigDecimal("5.00"));

        SalesSettingsDto dto = service.update(new UpdateSalesSettingsRequest(
                COMPANY_UID, false, null, "TZS", false, null,
                DiscountApprovalAction.OFF, null));

        assertThat(dto.discountApprovalAction())
                .as("a deliberate OFF must be honoured — only an ABSENT stance is ignored")
                .isEqualTo(DiscountApprovalAction.OFF);
    }

    @Test
    void update_togglesAllowNegativeStock_onExistingRow() {
        SalesSettings existing = new SalesSettings(COMPANY_ID, 1L);
        existing.setAllowNegativeStock(false);
        Company company = company(COMPANY_ID);
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(existing));
        when(settings.save(org.mockito.ArgumentMatchers.any(SalesSettings.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        UpdateSalesSettingsRequest req = new UpdateSalesSettingsRequest(
                COMPANY_UID, false, null, "TZS", true);

        SalesSettingsDto dto = service.update(req);

        assertThat(dto.allowNegativeStock())
                .as("company opts into backorder — negative stock no longer blocked")
                .isTrue();
    }

    // -------------------------------------------------------------------------

    private static Company company(Long id) {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(id);
        return c;
    }

    /**
     * Stubs the company lookup, an existing settings row and a pass-through save, and returns the
     * row so a test can assert on the entity the service actually mutated.
     */
    private SalesSettings existingRow() {
        SalesSettings existing = new SalesSettings(COMPANY_ID, 1L);
        // company(...) stubs a mock itself, so it MUST be built before the outer when(...) opens —
        // stubbing inside an in-flight when(...) argument is what Mockito calls unfinished stubbing.
        Company company = company(COMPANY_ID);
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(existing));
        when(settings.save(org.mockito.ArgumentMatchers.any(SalesSettings.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        return existing;
    }
}
