package com.erp.modules.sales.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.GlConfig;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.GlConfigRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Unit tests for {@link TillExpenseGlSeeder} — the V97 provisioning path.
 *
 * <p>The rule being protected is "provisioning over data migrations": the POS_TILL_EXPENSE account
 * is per-tenant data, so V97 widens the {@code gl_configs} key CHECK and nothing else, and the
 * mapping is created here — for a new company at provisioning time, and for every company that
 * already existed at upgrade time on the next application start.
 *
 * <p>The transaction manager is a bare mock: {@code TransactionTemplate} still runs the callback
 * against it, which is all these tests need. What the template guarantees in production (a nested,
 * independently committed transaction) is a wiring property, not a behavioural one.
 */
class TillExpenseGlSeederTest {

    private ChartOfAccountRepository accounts;
    private GlConfigRepository       configs;
    private CompanyRepository        companies;
    private TillExpenseGlSeeder      seeder;

    @BeforeEach
    void setUp() {
        accounts  = mock(ChartOfAccountRepository.class);
        configs   = mock(GlConfigRepository.class);
        companies = mock(CompanyRepository.class);
        seeder    = new TillExpenseGlSeeder(accounts, configs, companies,
                mock(PlatformTransactionManager.class));
    }

    // -------------------------------------------------------------------------
    // Seeding a company
    // -------------------------------------------------------------------------

    /**
     * The upgrade case: a tenant already trading when V97 lands has no POS_TILL_EXPENSE mapping and
     * must not have to run a manual step before an expense can post. Both halves are created in one
     * go, and the account created is an EXPENSE account — the classification is the whole point.
     */
    @Test
    void seedsTheAccountAndTheMappingForACompanyThatHasNeither() {
        unmapped(7L);
        when(accounts.findByCompanyIdAndAccountCode(7L, "5175")).thenReturn(Optional.empty());
        when(accounts.save(any())).thenAnswer(inv -> withId(inv.getArgument(0), 517500L));

        seeder.seedDefaults(7L);

        ArgumentCaptor<ChartOfAccount> account = ArgumentCaptor.forClass(ChartOfAccount.class);
        verify(accounts).save(account.capture());
        Assertions.assertThat(account.getValue().getAccountCode()).isEqualTo("5175");
        Assertions.assertThat(account.getValue().getName()).isEqualTo("Till Expenses");
        Assertions.assertThat(account.getValue().getAccountType()).isEqualTo(AccountType.EXPENSE);

        ArgumentCaptor<GlConfig> config = ArgumentCaptor.forClass(GlConfig.class);
        verify(configs).save(config.capture());
        Assertions.assertThat(config.getValue().getConfigKey())
                .isEqualTo(GlConfigKey.POS_TILL_EXPENSE);
        Assertions.assertThat(config.getValue().getAccountId()).isEqualTo(517500L);
    }

    /**
     * A tenant who already keeps a 5175 expense account of their own keeps it — the seeder adopts
     * what is there rather than creating a second account with the same code, which the chart would
     * reject anyway.
     */
    @Test
    void adoptsAnExistingAccountRatherThanDuplicatingIt() {
        unmapped(7L);
        when(accounts.findByCompanyIdAndAccountCode(7L, "5175"))
                .thenReturn(Optional.of(account(999L, AccountType.EXPENSE, true)));

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

    // -------------------------------------------------------------------------
    // The account it refuses to adopt — the original bug, re-entering by the back door
    // -------------------------------------------------------------------------

    /**
     * The reuse this whole change exists to stop. If a tenant's 5175 happens to be the account they
     * post drawer shortages to, adopting it would send till expenses straight back into the variance
     * account — the exact misstatement V97 closes. Leave it unmapped and let the expense be refused;
     * a finance user choosing the right account is the only correct resolution.
     */
    @Test
    void neverMapsTheKeyToTheCashShortAccount() {
        unmapped(3L);
        ChartOfAccount cashShort = account(5170L, AccountType.EXPENSE, true);
        when(accounts.findByCompanyIdAndAccountCode(3L, "5175"))
                .thenReturn(Optional.of(cashShort));
        when(configs.findByCompanyIdAndConfigKey(3L, GlConfigKey.POS_CASH_SHORT))
                .thenReturn(Optional.of(new GlConfig(3L, GlConfigKey.POS_CASH_SHORT, 5170L, null)));

        seeder.seedDefaults(3L);

        verify(configs, never()).save(any());
        verify(accounts, never()).save(any());
    }

    /**
     * An account of the wrong nature is not silently pressed into service: mapping expenses to, say,
     * an asset account would misclassify the P&amp;L just as badly as the shortage account did.
     */
    @Test
    void refusesToAdoptAnAccountThatIsNotAnExpenseAccount() {
        unmapped(3L);
        when(accounts.findByCompanyIdAndAccountCode(3L, "5175"))
                .thenReturn(Optional.of(account(41L, AccountType.ASSET, true)));

        seeder.seedDefaults(3L);

        verify(configs, never()).save(any());
    }

    /**
     * An inactive account would resolve to a posting failure on the very next expense, so mapping to
     * it just moves the refusal somewhere less explicable.
     */
    @Test
    void refusesToAdoptAnInactiveAccount() {
        unmapped(3L);
        when(accounts.findByCompanyIdAndAccountCode(3L, "5175"))
                .thenReturn(Optional.of(account(42L, AccountType.EXPENSE, false)));

        seeder.seedDefaults(3L);

        verify(configs, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // ensureMapping — the durable, concurrency-safe path
    // -------------------------------------------------------------------------

    /**
     * The concurrency case named in the requirement: two tills recording an expense at the same
     * instant both see no mapping, and the loser's insert hits
     * {@code uq_gl_config_company_key}. That must not surface — the row it wanted exists, which is
     * the outcome asked for, and the losing till goes on to record its expense.
     */
    @Test
    void ensureMapping_swallowsTheLostRaceOnTheUniqueConstraint() {
        unmapped(7L);
        when(accounts.findByCompanyIdAndAccountCode(7L, "5175")).thenReturn(Optional.empty());
        when(accounts.save(any())).thenAnswer(inv -> withId(inv.getArgument(0), 5175L));
        when(configs.save(any())).thenThrow(new DataIntegrityViolationException("uq_gl_config_company_key"));

        Assertions.assertThatCode(() -> seeder.ensureMapping(7L)).doesNotThrowAnyException();
    }

    /**
     * {@code ensureMapping} is called from the posting path and is documented never to throw: when
     * it cannot provision, the caller's resolve step refuses the expense with a message finance can
     * act on. A raw failure here would replace that with a meaningless error at the till.
     */
    @Test
    void ensureMapping_neverThrowsWhenProvisioningFails() {
        unmapped(7L);
        when(accounts.findByCompanyIdAndAccountCode(7L, "5175")).thenReturn(Optional.empty());
        when(accounts.save(any())).thenThrow(new IllegalStateException("chart unavailable"));

        Assertions.assertThatCode(() -> seeder.ensureMapping(7L)).doesNotThrowAnyException();
        verify(configs, never()).save(any());
    }

    /** Already mapped: one indexed read, no write, nothing suspended. */
    @Test
    void ensureMapping_isANoOpOnceTheMappingExists() {
        when(configs.findByCompanyIdAndConfigKey(7L, GlConfigKey.POS_TILL_EXPENSE))
                .thenReturn(Optional.of(mock(GlConfig.class)));

        Assertions.assertThat(seeder.ensureMapping(7L)).isFalse();
        verify(accounts, never()).findByCompanyIdAndAccountCode(any(), any());
        verify(configs, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // healMissingDefaults — the trigger whose absence was the defect
    // -------------------------------------------------------------------------

    /**
     * The root cause this closes. Provisioning only ever ran when a company was <em>created</em>, so
     * every company that predated V97 had no mapping and every till expense was refused. Healing on
     * start-up means an upgrade fixes itself, for each existing tenant, before anyone touches the
     * feature.
     */
    @Test
    void healMissingDefaults_seedsEveryExistingCompanyOnStartup() {
        // Built before the findAll() stub: building a mock inside when(...) is unfinished stubbing.
        List<Company> tenants = List.of(company(1L, MasterStatus.ACTIVE), company(2L, MasterStatus.ACTIVE));
        when(companies.findAll()).thenReturn(tenants);
        unmapped(1L);
        unmapped(2L);
        when(accounts.findByCompanyIdAndAccountCode(any(), any())).thenReturn(Optional.empty());
        when(accounts.save(any())).thenAnswer(inv -> withId(inv.getArgument(0), 5175L));

        seeder.healMissingDefaults();

        ArgumentCaptor<GlConfig> saved = ArgumentCaptor.forClass(GlConfig.class);
        verify(configs, org.mockito.Mockito.times(2)).save(saved.capture());
        Assertions.assertThat(saved.getAllValues()).extracting(GlConfig::getCompanyId)
                .containsExactlyInAnyOrder(1L, 2L);
    }

    /** Archived companies take no till expenses, so they are not given a chart account either. */
    @Test
    void healMissingDefaults_skipsArchivedCompanies() {
        List<Company> tenants = List.of(company(9L, MasterStatus.ARCHIVED));
        when(companies.findAll()).thenReturn(tenants);

        seeder.healMissingDefaults();

        verify(configs, never()).findByCompanyIdAndConfigKey(any(), any());
        verify(configs, never()).save(any());
    }

    /**
     * One tenant's unusable chart must not stop the others being healed, and a self-heal must never
     * take the application down on start-up.
     */
    @Test
    void healMissingDefaults_oneCompanyFailingDoesNotStopTheRest() {
        List<Company> tenants = List.of(company(1L, MasterStatus.ACTIVE), company(2L, MasterStatus.ACTIVE));
        when(companies.findAll()).thenReturn(tenants);
        unmapped(1L);
        unmapped(2L);
        when(accounts.findByCompanyIdAndAccountCode(1L, "5175"))
                .thenThrow(new IllegalStateException("chart unavailable"));
        when(accounts.findByCompanyIdAndAccountCode(2L, "5175")).thenReturn(Optional.empty());
        when(accounts.save(any())).thenAnswer(inv -> withId(inv.getArgument(0), 5175L));

        Assertions.assertThatCode(() -> seeder.healMissingDefaults()).doesNotThrowAnyException();

        ArgumentCaptor<GlConfig> saved = ArgumentCaptor.forClass(GlConfig.class);
        verify(configs).save(saved.capture());
        Assertions.assertThat(saved.getValue().getCompanyId()).isEqualTo(2L);
    }

    /** Nothing about a self-heal is worth failing a boot over — not even being unable to list tenants. */
    @Test
    void healMissingDefaults_survivesAFailureToListCompanies() {
        when(companies.findAll()).thenThrow(new IllegalStateException("db down"));

        Assertions.assertThatCode(() -> seeder.healMissingDefaults()).doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private void unmapped(Long companyId) {
        when(configs.findByCompanyIdAndConfigKey(companyId, GlConfigKey.POS_TILL_EXPENSE))
                .thenReturn(Optional.empty());
    }

    private static ChartOfAccount account(Long id, AccountType type, boolean active) {
        ChartOfAccount account = new ChartOfAccount(7L, "5175", "Till Expenses", type, null);
        account.setActive(active);
        return withId(account, id);
    }

    private static ChartOfAccount withId(ChartOfAccount account, Long id) {
        org.springframework.test.util.ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    private static Company company(Long id, MasterStatus status) {
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(id);
        when(company.getStatus()).thenReturn(status);
        return company;
    }
}
