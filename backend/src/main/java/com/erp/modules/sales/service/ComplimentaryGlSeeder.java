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
 * Provisions the {@value #ACCOUNT_CODE} complimentary-expense account and its
 * {@link GlConfigKey#COMPLIMENTARY_EXPENSE} mapping per company — same triggers as
 * {@link TillExpenseGlSeeder}.
 */
@Component
public class ComplimentaryGlSeeder {

    private static final Logger log = LoggerFactory.getLogger(ComplimentaryGlSeeder.class);

    static final String ACCOUNT_CODE = "5176";
    static final String ACCOUNT_NAME = "Complimentary Expense";

    private final ChartOfAccountRepository accounts;
    private final GlConfigRepository       configs;
    private final CompanyRepository        companies;
    private final TransactionTemplate ownTransaction;

    public ComplimentaryGlSeeder(ChartOfAccountRepository accounts,
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

    /** Every non-archived company gets its mapping once the application is up; never throws. */
    @EventListener(ApplicationReadyEvent.class)
    public void healMissingDefaults() {
        List<Company> all;
        try {
            all = companies.findAll();
        } catch (RuntimeException ex) {
            log.warn("ComplimentaryGlSeeder: could not list companies to heal the mapping.", ex);
            return;
        }
        int healed = 0;
        for (Company company : all) {
            if (company.getStatus() != MasterStatus.ARCHIVED && ensureMapping(company.getId())) {
                healed++;
            }
        }
        if (healed > 0) {
            log.info("ComplimentaryGlSeeder: healed the complimentary GL mapping for {} of {} "
                    + "company/companies.", healed, all.size());
        }
    }

    /** Company provisioning: joins the caller's transaction (the company row is not committed). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void seedDefaults(Long companyId) {
        seedIfMissing(companyId);
    }

    /** Ensures the mapping in its own transaction; never throws. True when it created it. */
    public boolean ensureMapping(Long companyId) {
        if (isMapped(companyId)) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(ownTransaction.execute(status -> seedIfMissing(companyId)));
        } catch (DataIntegrityViolationException ex) {
            log.debug("ComplimentaryGlSeeder: concurrent seed for company {} — already mapped.",
                    companyId);
            return false;
        } catch (RuntimeException ex) {
            log.warn("ComplimentaryGlSeeder: could not provision the mapping for company {}: {}",
                    companyId, ex.toString());
            return false;
        }
    }

    /** True when the company has an account mapped to COMPLIMENTARY_EXPENSE. */
    public boolean isMapped(Long companyId) {
        return configs.findByCompanyIdAndConfigKey(companyId, GlConfigKey.COMPLIMENTARY_EXPENSE)
                .isPresent();
    }

    private boolean seedIfMissing(Long companyId) {
        if (isMapped(companyId)) {
            return false;
        }
        Optional<ChartOfAccount> account = resolveOrCreateExpenseAccount(companyId);
        if (account.isEmpty()) {
            return false;
        }
        configs.save(new GlConfig(companyId, GlConfigKey.COMPLIMENTARY_EXPENSE,
                account.get().getId(), null));
        log.info("ComplimentaryGlSeeder: mapped COMPLIMENTARY_EXPENSE to account {} for company {}.",
                ACCOUNT_CODE, companyId);
        return true;
    }

    private Optional<ChartOfAccount> resolveOrCreateExpenseAccount(Long companyId) {
        Optional<ChartOfAccount> existing =
                accounts.findByCompanyIdAndAccountCode(companyId, ACCOUNT_CODE);
        if (existing.isEmpty()) {
            ChartOfAccount created = accounts.save(new ChartOfAccount(
                    companyId, ACCOUNT_CODE, ACCOUNT_NAME, AccountType.EXPENSE, null));
            log.info("ComplimentaryGlSeeder: seeded account {} '{}' for company {}.",
                    ACCOUNT_CODE, ACCOUNT_NAME, companyId);
            return Optional.of(created);
        }
        ChartOfAccount account = existing.get();
        if (account.getAccountType() != AccountType.EXPENSE || !account.isActive()) {
            log.warn("ComplimentaryGlSeeder: account {} for company {} is not an active EXPENSE "
                    + "account — leaving COMPLIMENTARY_EXPENSE unmapped for a finance user to set.",
                    ACCOUNT_CODE, companyId);
            return Optional.empty();
        }
        return existing;
    }
}
