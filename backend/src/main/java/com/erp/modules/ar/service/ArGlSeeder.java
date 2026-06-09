package com.erp.modules.ar.service;

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
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the two AR-specific GL accounts (5500 Bad Debt Expense, 3100 Opening Balance Equity)
 * and their gl_configs mappings for a new company (ADR-0014 D-13, BootstrapRunner pattern).
 * Called by BootstrapRunner and CompanyService.create for fresh companies.
 * Idempotent — skips if the account/config already exists.
 */
@Component
public class ArGlSeeder {

    private static final Logger log = LoggerFactory.getLogger(ArGlSeeder.class);

    private final ChartOfAccountRepository accounts;
    private final GlConfigRepository configs;

    public ArGlSeeder(ChartOfAccountRepository accounts, GlConfigRepository configs) {
        this.accounts = accounts;
        this.configs  = configs;
    }

    @Transactional
    public void seedDefaults(Long companyId) {
        seedAccount(companyId, "5500", "Bad Debt Expense",       AccountType.EXPENSE,
                GlConfigKey.BAD_DEBT_EXPENSE);
        seedAccount(companyId, "3100", "Opening Balance Equity", AccountType.EQUITY,
                GlConfigKey.OPENING_BALANCE_EQUITY);
    }

    private void seedAccount(Long companyId, String code, String name,
                              AccountType type, GlConfigKey configKey) {
        Optional<ChartOfAccount> existing = accounts.findByCompanyIdAndAccountCode(companyId, code);
        ChartOfAccount acct;
        if (existing.isEmpty()) {
            acct = accounts.save(new ChartOfAccount(companyId, code, name, type, null));
            log.info("ArGlSeeder: seeded account {} '{}' for company {}.", code, name, companyId);
        } else {
            acct = existing.get();
        }

        if (configs.findByCompanyIdAndConfigKey(companyId, configKey).isEmpty()) {
            configs.save(new GlConfig(companyId, configKey, acct.getId(), null));
            log.info("ArGlSeeder: seeded gl_config {} → {} for company {}.", configKey, code, companyId);
        }
    }
}
