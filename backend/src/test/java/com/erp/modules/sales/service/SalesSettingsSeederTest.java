package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link SalesSettingsSeederImpl} — the per-company Sales Settings row provisioned by
 * {@code CompanyProvisioningService}. Idempotency is load-bearing here, not a nicety: the same
 * seeder chain runs again on every re-provision, which is how already-created companies get healed.
 */
@ExtendWith(MockitoExtension.class)
class SalesSettingsSeederTest {

    private static final Long COMPANY_ID = 7L;

    @Mock private SalesSettingsRepository settings;

    @InjectMocks private SalesSettingsSeederImpl seeder;

    @Test
    void seedDefaults_noRowYet_createsBlockingRow() {
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());

        seeder.seedDefaults(COMPANY_ID);

        ArgumentCaptor<SalesSettings> saved = ArgumentCaptor.forClass(SalesSettings.class);
        verify(settings).save(saved.capture());
        assertThat(saved.getValue().getCompanyId()).isEqualTo(COMPANY_ID);
        assertThat(saved.getValue().isAllowNegativeStock())
                .as("the provisioned default must BLOCK negative stock, matching the entity/DB "
                        + "default and what the Sales Settings screen shows")
                .isFalse();
        assertThat(saved.getValue().isSoApprovalEnabled()).isFalse();
    }

    @Test
    void seedDefaults_rowAlreadyExists_isANoOp() {
        SalesSettings existing = new SalesSettings(COMPANY_ID, 1L);
        existing.setAllowNegativeStock(true); // the company deliberately opted into backorder
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(existing));

        seeder.seedDefaults(COMPANY_ID);

        verify(settings, never()).save(any());
        assertThat(existing.isAllowNegativeStock())
                .as("re-provisioning must never reset a company's own choice")
                .isTrue();
    }

    @Test
    void seedDefaults_runTwice_createsExactlyOneRow() {
        // First run sees nothing and inserts; the second run sees the row the first one wrote.
        SalesSettings created = new SalesSettings(COMPANY_ID, null);
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(created));

        seeder.seedDefaults(COMPANY_ID);
        seeder.seedDefaults(COMPANY_ID);

        verify(settings, org.mockito.Mockito.times(1)).save(any());
    }
}
