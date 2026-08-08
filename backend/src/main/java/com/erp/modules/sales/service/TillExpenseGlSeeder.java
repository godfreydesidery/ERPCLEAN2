package com.erp.modules.sales.service;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.GlConfig;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.GlConfigRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
 * <p><b>Self-healing, and why that matters.</b> {@link #seedDefaults(Long)} is idempotent and is
 * called from two places:
 * <ul>
 *   <li>{@code CompanyProvisioningService.provisionDefaults} — every new company, and every
 *       re-provision of an existing one;</li>
 *   <li>{@link PosSessionServiceImpl}, on the till-expense posting path, immediately before the
 *       account is resolved — so a tenant that upgrades into V97 with sessions already running gets
 *       the mapping the first time an expense is recorded, with no manual step and no downtime.</li>
 * </ul>
 * The hot-path call short-circuits on a single indexed read once the mapping exists, so the
 * self-heal costs one query per expense and nothing else.
 *
 * <p><b>What it never does.</b> It does not touch, re-point or back-post existing payout rows.
 * Historical payouts were posted (or not posted) under the rules of their own period and stay
 * exactly as they are; restating them is an owner decision handled operationally.
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

    public TillExpenseGlSeeder(ChartOfAccountRepository accounts, GlConfigRepository configs) {
        this.accounts = accounts;
        this.configs  = configs;
    }

    /**
     * Ensures this company has an account mapped to {@link GlConfigKey#POS_TILL_EXPENSE}.
     *
     * <p>Idempotent in both halves: an existing mapping returns immediately, and an existing
     * {@value #ACCOUNT_CODE} account is adopted rather than duplicated — a tenant who already
     * created their own till-expense account keeps it.
     *
     * <p>{@code Propagation.REQUIRED}: joins the caller's transaction. On the expense path that is
     * deliberate — if the expense rolls back, so does the mapping it created, and the next attempt
     * re-creates it cleanly.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void seedDefaults(Long companyId) {
        if (configs.findByCompanyIdAndConfigKey(companyId, GlConfigKey.POS_TILL_EXPENSE).isPresent()) {
            return;
        }

        Optional<ChartOfAccount> existing =
                accounts.findByCompanyIdAndAccountCode(companyId, ACCOUNT_CODE);
        ChartOfAccount account = existing.orElseGet(() -> {
            log.info("TillExpenseGlSeeder: seeded account {} '{}' for company {}.",
                    ACCOUNT_CODE, ACCOUNT_NAME, companyId);
            return accounts.save(new ChartOfAccount(companyId, ACCOUNT_CODE, ACCOUNT_NAME,
                    AccountType.EXPENSE, null));
        });

        configs.save(new GlConfig(companyId, GlConfigKey.POS_TILL_EXPENSE, account.getId(), null));
        log.info("TillExpenseGlSeeder: seeded gl_config {} → {} for company {}.",
                GlConfigKey.POS_TILL_EXPENSE, ACCOUNT_CODE, companyId);
    }
}
