package com.erp.modules.fixedassets.service;

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
 * Seeds the six Fixed Assets GL accounts + gl_configs mappings for a new company
 * (ADR-0030 D-11; BootstrapRunner + CompanyService.create pattern).
 * Idempotent — skips if the account/config already exists.
 */
@Component
public class FixedAssetGlSeeder {

    private static final Logger log = LoggerFactory.getLogger(FixedAssetGlSeeder.class);

    private final ChartOfAccountRepository accounts;
    private final GlConfigRepository       configs;

    public FixedAssetGlSeeder(ChartOfAccountRepository accounts, GlConfigRepository configs) {
        this.accounts = accounts;
        this.configs  = configs;
    }

    @Transactional
    public void seedDefaults(Long companyId) {
        seedAccount(companyId, "1600", "Fixed Assets (at cost)",
                AccountType.ASSET, GlConfigKey.FIXED_ASSETS);
        seedAccount(companyId, "1650", "Fixed Asset Acquisition/Disposal Clearing",
                AccountType.ASSET, GlConfigKey.FIXED_ASSET_CLEARING);
        seedAccount(companyId, "1700", "Accumulated Depreciation",
                AccountType.ASSET, GlConfigKey.ACCUMULATED_DEPRECIATION);
        seedAccount(companyId, "3200", "Asset Revaluation Reserve",
                AccountType.EQUITY, GlConfigKey.REVALUATION_RESERVE);
        seedAccount(companyId, "4200", "Gain / (Loss) on Asset Disposal",
                AccountType.INCOME, GlConfigKey.GAIN_LOSS_ON_DISPOSAL);
        seedAccount(companyId, "5500", "Depreciation Expense",
                AccountType.EXPENSE, GlConfigKey.DEPRECIATION_EXPENSE);
    }

    private void seedAccount(Long companyId, String code, String name,
                               AccountType type, GlConfigKey configKey) {
        Optional<ChartOfAccount> existing = accounts.findByCompanyIdAndAccountCode(companyId, code);
        ChartOfAccount acct;
        if (existing.isEmpty()) {
            acct = accounts.save(new ChartOfAccount(companyId, code, name, type, null));
            log.info("FixedAssetGlSeeder: seeded account {} '{}' for company {}.", code, name, companyId);
        } else {
            acct = existing.get();
        }

        if (configs.findByCompanyIdAndConfigKey(companyId, configKey).isEmpty()) {
            configs.save(new GlConfig(companyId, configKey, acct.getId(), null));
            log.info("FixedAssetGlSeeder: seeded gl_config {} → {} for company {}.",
                    configKey, code, companyId);
        }
    }
}
