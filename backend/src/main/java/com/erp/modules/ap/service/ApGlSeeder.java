package com.erp.modules.ap.service;

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
 * Seeds the AP-specific GL account (5150 Purchases) and its gl_configs mapping for a new company
 * (ADR-0015 D-13, BootstrapRunner pattern). Called by BootstrapRunner and CompanyService.create.
 * Idempotent — skips if the account/config already exists.
 */
@Component
public class ApGlSeeder {

    private static final Logger log = LoggerFactory.getLogger(ApGlSeeder.class);

    private final ChartOfAccountRepository accounts;
    private final GlConfigRepository       configs;

    public ApGlSeeder(ChartOfAccountRepository accounts, GlConfigRepository configs) {
        this.accounts = accounts;
        this.configs  = configs;
    }

    @Transactional
    public void seedDefaults(Long companyId) {
        seedAccount(companyId, "5150", "Purchases", AccountType.EXPENSE, GlConfigKey.PURCHASES);
        // OPENING_BALANCE_EQUITY is seeded by ArGlSeeder; ensure idempotent if AP seeds first
        seedAccount(companyId, "3100", "Opening Balance Equity", AccountType.EQUITY,
                GlConfigKey.OPENING_BALANCE_EQUITY);
    }

    private void seedAccount(Long companyId, String code, String name,
                              AccountType type, GlConfigKey configKey) {
        Optional<ChartOfAccount> existing = accounts.findByCompanyIdAndAccountCode(companyId, code);
        ChartOfAccount acct;
        if (existing.isEmpty()) {
            acct = accounts.save(new ChartOfAccount(companyId, code, name, type, null));
            log.info("ApGlSeeder: seeded account {} '{}' for company {}.", code, name, companyId);
        } else {
            acct = existing.get();
        }

        if (configs.findByCompanyIdAndConfigKey(companyId, configKey).isEmpty()) {
            configs.save(new GlConfig(companyId, configKey, acct.getId(), null));
            log.info("ApGlSeeder: seeded gl_config {} → {} for company {}.", configKey, code, companyId);
        }
    }
}
