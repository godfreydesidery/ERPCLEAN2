package com.erp.modules.stock.service;

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
 * Seeds the inventory-valuation GL accounts (2150 GRNI + 5160 Stock Adjustment/Shrinkage) and
 * their {@code gl_configs} mappings for a new company (ADR-0020 D-8, BootstrapRunner pattern).
 *
 * <p>Called by {@link com.erp.platform.bootstrap.BootstrapRunner} and
 * {@link com.erp.modules.iam.service.CompanyServiceImpl} (if present) so every new company
 * gets these accounts regardless of when the company is created relative to the migration.
 * Idempotent — skips if the account/config already exists.
 */
@Component
public class InventoryGlSeeder {

    private static final Logger log = LoggerFactory.getLogger(InventoryGlSeeder.class);

    private final ChartOfAccountRepository accounts;
    private final GlConfigRepository       configs;

    public InventoryGlSeeder(ChartOfAccountRepository accounts, GlConfigRepository configs) {
        this.accounts = accounts;
        this.configs  = configs;
    }

    @Transactional
    public void seedDefaults(Long companyId) {
        seedAccount(companyId, "2150", "Goods Received Not Invoiced",
                AccountType.LIABILITY, GlConfigKey.GRNI);
        seedAccount(companyId, "5160", "Stock Adjustment / Shrinkage",
                AccountType.EXPENSE, GlConfigKey.STOCK_ADJUSTMENT);
    }

    private void seedAccount(Long companyId, String code, String name,
                              AccountType type, GlConfigKey configKey) {
        Optional<ChartOfAccount> existing = accounts.findByCompanyIdAndAccountCode(companyId, code);
        ChartOfAccount acct;
        if (existing.isEmpty()) {
            acct = accounts.save(new ChartOfAccount(companyId, code, name, type, null));
            log.info("InventoryGlSeeder: seeded account {} '{}' for company {}.", code, name, companyId);
        } else {
            acct = existing.get();
        }

        if (configs.findByCompanyIdAndConfigKey(companyId, configKey).isEmpty()) {
            configs.save(new GlConfig(companyId, configKey, acct.getId(), null));
            log.info("InventoryGlSeeder: seeded gl_config {} → {} for company {}.",
                    configKey, code, companyId);
        }
    }
}
