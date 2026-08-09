package com.erp.modules.sales.service;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Provisions the till-expense GL account ({@value #ACCOUNT_CODE} "{@value #ACCOUNT_NAME}") and its
 * {@link GlConfigKey#POS_TILL_EXPENSE} mapping for a company (V97).
 *
 * <p><b>Why this exists.</b> Cash paid out of a drawer for a legitimate operating expense used to be
 * debited to whatever account was mapped to {@link GlConfigKey#POS_CASH_SHORT} — literally
 * "Cash Short / Till Shortage". That is two misstatements from one shortcut: a variance account
 * whose whole purpose is detecting drawer discrepancies gets filled with routine spend (so a genuine
 * shortage becomes indistinguishable from a bus fare), and operating expense is misclassified by
 * nature on the P&amp;L. V97 widened the {@code gl_configs} key CHECK to admit
 * {@code POS_TILL_EXPENSE}; this class supplies the per-tenant account behind it.
 *
 * <p><b>Why in application code and not SQL.</b> Standing rule: provisioning over data migrations.
 * The account is per-tenant data, so V97 widens the constraint and nothing else — the mapping is
 * created here.
 *
 * <p><b>The defect this class had to be fixed for.</b> It shipped with only two triggers:
 * {@code CompanyProvisioningService.provisionDefaults} (which runs when a company is <em>created</em>,
 * or when an administrator hits {@code POST /api/v1/companies/uid/{uid}/provision-defaults} — a
 * COMPANY.MANAGE action nobody was told to run), and an inline call on the till-expense posting path.
 * Every company that already existed when V97 landed therefore had no mapping, and the inline call
 * could not durably create one: it ran with {@link Propagation#REQUIRED}, i.e. inside the expense's
 * own transaction, so anything that failed later in that transaction rolled the freshly-created
 * account and mapping back with it. Result on the upgraded QA database: no
 * {@code gl_configs} row with {@code config_key = 'POS_TILL_EXPENSE'} for any company, and every till
 * expense refused. The same would have happened to every existing customer on upgrade.
 *
 * <p><b>How it heals now.</b> Three triggers, in order of when they bite:
 * <ul>
 *   <li>{@link #healMissingDefaults()} — on every application start, for every non-archived company.
 *       This is what fixes an existing tenant on upgrade, before any user touches the feature and
 *       with no manual step. It mirrors {@code DocumentBrandingSeeder.healMissingDefaults}, the
 *       established pattern for exactly this problem;</li>
 *   <li>{@link #seedDefaults(Long)} — company provisioning / re-provisioning, joining that
 *       transaction (the company row it references is not committed yet, so it must);</li>
 *   <li>{@link #ensureMapping(Long)} — the till-expense posting path, as a last-resort self-heal for
 *       a company created between two boots. It commits in its OWN transaction, so the mapping
 *       survives whatever the expense does next, and a lost race cannot poison the caller's
 *       transaction.</li>
 * </ul>
 *
 * <p><b>What it never does.</b> It does not touch, re-point or back-post existing payout rows.
 * Historical payouts were posted (or not posted) under the rules of their own period and stay
 * exactly as they are; restating them is an owner decision handled operationally. It also never
 * maps the key to the {@link GlConfigKey#POS_CASH_SHORT} account — that reuse is the whole bug.
 */
@Component
public class TillExpenseGlSeeder {

    private static final Logger log = LoggerFactory.getLogger(TillExpenseGlSeeder.class);

    /**
     * Sits in the 51xx expense block beside 5160 (Stock Adjustment) and 5170 (Cash Short), so a
     * printed P&amp;L reads "till expenses" next to the other operational cost lines rather than
     * inside them. Unused by every prior seed, so adopting it cannot collide with a tenant's
     * existing chart.
     */
    static final String ACCOUNT_CODE = "5175";

    static final String ACCOUNT_NAME = "Till Expenses";

    private final ChartOfAccountRepository accounts;
    private final GlConfigRepository       configs;
    private final CompanyRepository        companies;

    /**
     * PROPAGATION_REQUIRES_NEW, expressed as a template rather than an annotation on purpose: the
     * heal loop below calls the seeding path on itself, and a self-invocation never passes through
     * the Spring proxy — an {@code @Transactional} annotation there would silently do nothing.
     */
    private final TransactionTemplate ownTransaction;

    public TillExpenseGlSeeder(ChartOfAccountRepository accounts,
                               GlConfigRepository configs,
                               CompanyRepository companies,
                               PlatformTransactionManager transactionManager) {
        this.accounts  = accounts;
        this.configs   = configs;
        this.companies = companies;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.ownTransaction = template;
    }

    // -------------------------------------------------------------------------
    // Trigger 1 — every application start (the upgrade path for existing tenants)
    // -------------------------------------------------------------------------

    /**
     * Gives every existing company its till-expense mapping once the application is up.
     *
     * <p>This is the trigger the original implementation lacked, and its absence is why an upgraded
     * database had no {@code POS_TILL_EXPENSE} row anywhere: provisioning only ever runs for a
     * company being created, and the posting-path self-heal cannot run until somebody records an
     * expense — which is the very thing that was refused.
     *
     * <p>Deliberately NOT transactional at the loop level: each company is seeded and committed on
     * its own, so one tenant's unusable chart can neither roll back nor stop the healing of the rest.
     * ARCHIVED companies are skipped — they take no till expenses. A failure to even list the
     * companies is logged and swallowed: a self-heal must never stop the application serving traffic.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void healMissingDefaults() {
        List<Company> all;
        try {
            all = companies.findAll();
        } catch (RuntimeException ex) {
            log.warn("TillExpenseGlSeeder: could not list companies to heal the till-expense mapping.",
                    ex);
            return;
        }
        int healed = 0;
        for (Company company : all) {
            if (company.getStatus() == MasterStatus.ARCHIVED) {
                continue;
            }
            if (ensureMapping(company.getId())) {
                healed++;
            }
        }
        if (healed > 0) {
            log.info("TillExpenseGlSeeder: healed the till-expense GL mapping for {} of {} company"
                    + "/companies.", healed, all.size());
        }
    }

    // -------------------------------------------------------------------------
    // Trigger 2 — company provisioning / re-provisioning
    // -------------------------------------------------------------------------

    /**
     * Ensures this company has an account mapped to {@link GlConfigKey#POS_TILL_EXPENSE}, joining the
     * caller's transaction.
     *
     * <p>{@link Propagation#REQUIRED} is required here, not merely convenient: provisioning runs
     * inside the transaction that created the company, whose {@code companies} row is not committed
     * yet. A separate transaction could not see it and both inserts would fail the
     * {@code fk_gl_config_company} / {@code fk_chart_of_account_company} foreign key.
     *
     * <p>Idempotent in both halves: an existing mapping returns immediately, and an existing
     * {@value #ACCOUNT_CODE} account is adopted rather than duplicated — a tenant who already
     * created their own till-expense account keeps it.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void seedDefaults(Long companyId) {
        seedIfMissing(companyId);
    }

    // -------------------------------------------------------------------------
    // Trigger 3 — the till-expense posting path
    // -------------------------------------------------------------------------

    /**
     * Ensures the mapping exists and <em>commits it independently of the caller</em>.
     *
     * <p>This is what {@code PosSessionServiceImpl} calls before resolving the expense account. Two
     * properties matter, and neither held before:
     * <ul>
     *   <li><b>Durable.</b> The write happens in its own transaction, so a mapping created here
     *       survives even if the expense that triggered it is refused a moment later for an unrelated
     *       reason. Previously the mapping rolled back with the expense and the tenant stayed stuck
     *       in the same state on every retry.</li>
     *   <li><b>Concurrency-safe.</b> Two tills recording an expense at the same instant can both pass
     *       the "is it missing?" check; the loser's insert violates {@code uq_gl_config_company_key}
     *       (or {@code uq_chart_of_account_company_code}) and is caught here. Because that insert was
     *       in a nested transaction of its own, the failure does not mark the caller's transaction
     *       rollback-only — the losing till still records its expense against the row the winner
     *       created.</li>
     * </ul>
     *
     * <p>Never throws. If the mapping genuinely cannot be provisioned, the caller's resolve step
     * fails next and refuses the expense with a message a finance user can act on — a refusal beats a
     * misposting.
     *
     * @return true when this call created the mapping (used by the heal loop's counter)
     */
    public boolean ensureMapping(Long companyId) {
        // Fast path: one indexed read, on the caller's transaction, for the overwhelmingly common
        // case where the mapping is already there. Nothing is suspended and nothing is written.
        if (isMapped(companyId)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(
                    ownTransaction.execute(status -> seedIfMissing(companyId)));
        } catch (DataIntegrityViolationException ex) {
            // Lost the race with a concurrent till (or another instance's heal loop). The row the
            // insert wanted now exists, which is exactly the outcome asked for.
            log.debug("TillExpenseGlSeeder: concurrent seed for company {} — mapping already exists.",
                    companyId);
            return false;
        } catch (RuntimeException ex) {
            // Technical detail to the log only; the caller refuses the expense with a friendly
            // message naming what finance must configure.
            log.warn("TillExpenseGlSeeder: could not provision the till-expense mapping for company"
                    + " {}: {}", companyId, ex.toString());
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private boolean isMapped(Long companyId) {
        return configs.findByCompanyIdAndConfigKey(companyId, GlConfigKey.POS_TILL_EXPENSE)
                .isPresent();
    }

    /**
     * The single seeding body every trigger shares, so the three paths cannot drift apart in what
     * they create or in what they refuse to adopt.
     *
     * @return true when a mapping was created by this call
     */
    private boolean seedIfMissing(Long companyId) {
        if (isMapped(companyId)) {
            return false;
        }
        Optional<ChartOfAccount> account = resolveOrCreateExpenseAccount(companyId);
        if (account.isEmpty()) {
            return false;
        }
        configs.save(new GlConfig(companyId, GlConfigKey.POS_TILL_EXPENSE,
                account.get().getId(), null));
        log.info("TillExpenseGlSeeder: seeded gl_config {} → {} for company {}.",
                GlConfigKey.POS_TILL_EXPENSE, ACCOUNT_CODE, companyId);
        return true;
    }

    /**
     * The account the mapping will point at: the company's {@value #ACCOUNT_CODE} account if it has
     * one and it is fit for the job, otherwise a newly created EXPENSE account.
     *
     * <p><b>Why an existing account is vetted rather than trusted.</b> Adopting whatever sits on code
     * {@value #ACCOUNT_CODE} would be how the original bug comes back: a chart where that code is an
     * archived stub, a non-expense account, or — worst — the very account mapped to
     * {@link GlConfigKey#POS_CASH_SHORT} would quietly send till expenses back into the variance
     * account this whole change exists to get them out of. When the code is occupied by something
     * unsuitable, this returns empty and the expense is refused with an actionable message, because a
     * finance user picking the right account is the only correct resolution — inventing a second
     * account code on their chart is not ours to do.
     *
     * @return the account to map, or empty when none can be resolved safely
     */
    private Optional<ChartOfAccount> resolveOrCreateExpenseAccount(Long companyId) {
        Optional<ChartOfAccount> existing =
                accounts.findByCompanyIdAndAccountCode(companyId, ACCOUNT_CODE);
        if (existing.isEmpty()) {
            ChartOfAccount created = accounts.save(new ChartOfAccount(
                    companyId, ACCOUNT_CODE, ACCOUNT_NAME, AccountType.EXPENSE, null));
            log.info("TillExpenseGlSeeder: seeded account {} '{}' for company {}.",
                    ACCOUNT_CODE, ACCOUNT_NAME, companyId);
            return Optional.of(created);
        }

        ChartOfAccount account = existing.get();
        if (account.getAccountType() != AccountType.EXPENSE) {
            log.warn("TillExpenseGlSeeder: account {} for company {} is {}, not EXPENSE — leaving"
                    + " POS_TILL_EXPENSE unmapped for a finance user to set.",
                    ACCOUNT_CODE, companyId, account.getAccountType());
            return Optional.empty();
        }
        if (!account.isActive()) {
            log.warn("TillExpenseGlSeeder: account {} for company {} is inactive — leaving"
                    + " POS_TILL_EXPENSE unmapped for a finance user to set.",
                    ACCOUNT_CODE, companyId);
            return Optional.empty();
        }
        if (isCashShortAccount(companyId, account.getId())) {
            log.warn("TillExpenseGlSeeder: account {} for company {} is the account mapped to {} —"
                    + " refusing to reuse the till-shortage account for till expenses.",
                    ACCOUNT_CODE, companyId, GlConfigKey.POS_CASH_SHORT);
            return Optional.empty();
        }
        return existing;
    }

    /** True when this account is the one the company posts drawer shortages to. */
    private boolean isCashShortAccount(Long companyId, Long accountId) {
        return configs.findByCompanyIdAndConfigKey(companyId, GlConfigKey.POS_CASH_SHORT)
                .map(GlConfig::getAccountId)
                .filter(id -> id.equals(accountId))
                .isPresent();
    }
}
