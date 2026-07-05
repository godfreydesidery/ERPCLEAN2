package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.sales.domain.dto.SalesSettingsDto;
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;
import com.erp.modules.sales.domain.entity.SalesSettings;
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
}
