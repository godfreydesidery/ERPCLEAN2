package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link PosSessionAgePolicy} (V113): the company's own limit wins, otherwise the server setting
 * applies — and the screen's "effective" number is the same answer the till enforces.
 */
class PosSessionAgePolicyTest {

    private static final Long COMPANY_ID = 7L;

    private final SalesSettingsRepository settings = mock(SalesSettingsRepository.class);

    @Test
    void companyLimitSet_winsOverServerSetting() {
        companyRow(72);
        PosSessionAgePolicy policy = new PosSessionAgePolicy(settings, Duration.ofHours(36));

        assertThat(policy.maxOpenAge(COMPANY_ID)).isEqualTo(Duration.ofHours(72));
        assertThat(policy.effectiveHours(72)).isEqualTo(72);
    }

    @Test
    void companyLimitNull_fallsBackToServerSetting() {
        companyRow(null);
        PosSessionAgePolicy policy = new PosSessionAgePolicy(settings, Duration.ofHours(36));

        assertThat(policy.maxOpenAge(COMPANY_ID)).isEqualTo(Duration.ofHours(36));
        assertThat(policy.effectiveHours(null)).isEqualTo(36);
    }

    @Test
    void noSettingsRowAtAll_fallsBackToServerSetting() {
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());
        PosSessionAgePolicy policy = new PosSessionAgePolicy(settings, Duration.ofHours(36));

        assertThat(policy.maxOpenAge(COMPANY_ID)).isEqualTo(Duration.ofHours(36));
    }

    /** Company "no limit" (0) wins over a server limit: the guard reads zero as off. */
    @Test
    void companyNoLimit_isZero_evenWhenTheServerHasALimit() {
        companyRow(0);
        PosSessionAgePolicy policy = new PosSessionAgePolicy(settings, Duration.ofHours(36));

        assertThat(policy.maxOpenAge(COMPANY_ID)).isEqualTo(Duration.ZERO);
        assertThat(policy.effectiveHours(0)).isZero();
    }

    /** Server setting switched off (PT0S): the guard reads zero as "off"; the screen shows 0. */
    @Test
    void serverSettingOff_isReportedAsZero_andStillYieldsToACompanyLimit() {
        PosSessionAgePolicy policy = new PosSessionAgePolicy(settings, Duration.ZERO);

        assertThat(policy.serverDefaultHours()).isZero();
        assertThat(policy.effectiveHours(null)).isZero();
        companyRow(24);
        assertThat(policy.maxOpenAge(COMPANY_ID)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void nullCompany_usesServerSetting_withoutTouchingTheRepository() {
        PosSessionAgePolicy policy = new PosSessionAgePolicy(settings, Duration.ofHours(36));

        assertThat(policy.maxOpenAge(null)).isEqualTo(Duration.ofHours(36));
    }

    private void companyRow(Integer hours) {
        SalesSettings row = new SalesSettings(COMPANY_ID, 1L);
        row.setPosSessionMaxOpenHours(hours);
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(row));
    }
}
