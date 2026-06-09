package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.GlConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the default CASH account for a new company, linked to the company's
 * gl_configs CASH mapping (ADR-0016 D-10, D-14). Idempotent — skips if already exists.
 * Called by BootstrapRunner for the bootstrap company and by CompanyService.create.
 */
@Component
public class CashBankSeeder {

    private static final Logger log = LoggerFactory.getLogger(CashBankSeeder.class);

    private final CashBankAccountRepository cashAccounts;
    private final GlConfigRepository        glConfigs;
    private final ChartOfAccountRepository  glAccountRepo;
    private final CashBankNumberGenerator   numbers;

    public CashBankSeeder(CashBankAccountRepository cashAccounts,
                           GlConfigRepository glConfigs,
                           ChartOfAccountRepository glAccountRepo,
                           CashBankNumberGenerator numbers) {
        this.cashAccounts  = cashAccounts;
        this.glConfigs     = glConfigs;
        this.glAccountRepo = glAccountRepo;
        this.numbers       = numbers;
    }

    /**
     * Seeds one default CASH account for the given company, pointing at the GL account
     * identified by the company's CASH gl_config mapping. Skips if a default account already
     * exists (idempotent) or if the GL CASH config has not been seeded yet.
     */
    @Transactional
    public void seedDefaults(Long companyId) {
        // Skip if a default already exists (migration seed or prior call covered it)
        if (cashAccounts.findByCompanyIdAndIsDefaultTrue(companyId).isPresent()) {
            log.debug("CashBankSeeder: default cash account already exists for company {}.", companyId);
            return;
        }

        // Resolve the GL account from CASH gl_config
        var glConfigOpt = glConfigs.findByCompanyIdAndConfigKey(companyId, GlConfigKey.CASH);
        if (glConfigOpt.isEmpty()) {
            log.warn("CashBankSeeder: no CASH gl_config found for company {} — skipping.", companyId);
            return;
        }

        Long glAccountId = glConfigOpt.get().getAccountId();

        // Skip if another account already owns this GL account (1:1 invariant)
        if (cashAccounts.findByCompanyIdAndGlAccountId(companyId, glAccountId).isPresent()) {
            log.debug("CashBankSeeder: GL account {} already linked to a cash account for company {} — skipping.",
                    glAccountId, companyId);
            return;
        }

        String glCode = glAccountRepo.findById(glAccountId)
                .map(a -> a.getAccountCode()).orElse("????");

        String code = numbers.nextAccount(companyId);
        String name = "Cash Account (" + glCode + ")";

        CashBankAccount account = new CashBankAccount(
                companyId, null, code, name,
                CashBankAccountType.CASH,
                "TZS",                  // base currency; the migration seeds TZS too
                glAccountId,
                true,                   // is_default = true
                null                    // system seed; no human actor
        );
        cashAccounts.save(account);

        log.info("CashBankSeeder: seeded default cash account '{}' '{}' → GL {} for company {}.",
                code, name, glCode, companyId);
    }
}
