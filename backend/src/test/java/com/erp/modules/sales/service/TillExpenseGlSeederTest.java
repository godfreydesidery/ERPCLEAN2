package com.erp.modules.sales.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.GlConfig;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.GlConfigRepository;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link TillExpenseGlSeeder} — the V97 provisioning path.
 *
 * <p>The rule being protected is "provisioning over data migrations": the POS_TILL_EXPENSE account
 * is per-tenant data, so V97 widens the {@code gl_configs} key CHECK and nothing else, and the
 * mapping is created here — for a new company at provisioning time, and for an existing one the
 * first time an expense is recorded after the upgrade.
 */
class TillExpenseGlSeederTest {

    private ChartOfAccountRepository accounts;
    private GlConfigRepository       configs;
    private TillExpenseGlSeeder      seeder;

    @BeforeEach
    void setUp() {
        accounts = mock(ChartOfAccountRepository.class);
        configs  = mock(GlConfigRepository.class);
        seeder   = new TillExpenseGlSeeder(accounts, configs);
    }

    /**
     * The upgrade case, and the reason this runs on the posting path: a tenant already trading when
     * V97 lands has no POS_TILL_EXPENSE mapping and must not have to run a manual step before an
     * expense can post. Both halves are created in one go.
     */
    @Test
    void seedsTheAccountAndTheMappingForACompanyThatHasNeither() {
        when(configs.findByCompanyIdAndConfigKey(7L, GlConfigKey.POS_TILL_EXPENSE))
                .thenReturn(Optional.empty());
        when(accounts.findByCompanyIdAndAccountCode(7L, "5175")).thenReturn(Optional.empty());
        ChartOfAccount created = mock(ChartOfAccount.class);
        when(created.getId()).thenReturn(517500L);
        when(accounts.save(any())).thenReturn(created);

        seeder.seedDefaults(7L);

        ArgumentCaptor<ChartOfAccount> account = ArgumentCaptor.forClass(ChartOfAccount.class);
        verify(accounts).save(account.capture());
        Assertions.assertThat(account.getValue().getAccountCode()).isEqualTo("5175");
        Assertions.assertThat(account.getValue().getName()).isEqualTo("Till Expenses");

        ArgumentCaptor<GlConfig> config = ArgumentCaptor.forClass(GlConfig.class);
        verify(configs).save(config.capture());
        Assertions.assertThat(config.getValue().getConfigKey())
                .isEqualTo(GlConfigKey.POS_TILL_EXPENSE);
        Assertions.assertThat(config.getValue().getAccountId()).isEqualTo(517500L);
    }

    /**
     * A tenant who already keeps a 5175 account of their own keeps it — the seeder adopts what is
     * there rather than creating a second account with the same code, which the chart would reject
     * anyway.
     */
    @Test
    void adoptsAnExistingAccountRatherThanDuplicatingIt() {
        when(configs.findByCompanyIdAndConfigKey(7L, GlConfigKey.POS_TILL_EXPENSE))
                .thenReturn(Optional.empty());
        ChartOfAccount existing = mock(ChartOfAccount.class);
        when(existing.getId()).thenReturn(999L);
        when(accounts.findByCompanyIdAndAccountCode(7L, "5175")).thenReturn(Optional.of(existing));

        seeder.seedDefaults(7L);

        verify(accounts, never()).save(any());
        ArgumentCaptor<GlConfig> config = ArgumentCaptor.forClass(GlConfig.class);
        verify(configs).save(config.capture());
        Assertions.assertThat(config.getValue().getAccountId()).isEqualTo(999L);
    }

    /**
     * Idempotent, and cheap: once mapped it short-circuits on a single indexed read. That is what
     * makes calling it on every till expense affordable — and what stops it re-pointing a mapping an
     * accountant has deliberately changed to their own account.
     */
    @Test
    void isANoOpOnceTheMappingExists() {
        when(configs.findByCompanyIdAndConfigKey(7L, GlConfigKey.POS_TILL_EXPENSE))
                .thenReturn(Optional.of(mock(GlConfig.class)));

        seeder.seedDefaults(7L);

        verify(accounts, never()).findByCompanyIdAndAccountCode(any(), any());
        verify(accounts, never()).save(any());
        verify(configs, never()).save(any());
    }
}
