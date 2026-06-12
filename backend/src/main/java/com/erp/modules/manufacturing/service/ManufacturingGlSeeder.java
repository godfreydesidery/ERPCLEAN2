package com.erp.modules.manufacturing.service;

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
 * Seeds the manufacturing WIP/variance GL accounts and their {@code gl_configs} mappings
 * for a new company (ADR-0035 D-7, BootstrapRunner pattern).
 *
 * <p>Accounts seeded:
 * <ul>
 *   <li>1320 – Work In Progress (WIP Inventory) — ASSET</li>
 *   <li>2350 – WIP Labour Clearing — LIABILITY</li>
 *   <li>2360 – WIP Overhead Clearing — LIABILITY</li>
 *   <li>5180 – Manufacturing Variance — EXPENSE</li>
 * </ul>
 * FINISHED_GOODS maps to the existing 1300 (Inventory) account — no new account needed.
 *
 * <p>Called by {@link com.erp.platform.bootstrap.BootstrapRunner} for every new company.
 * Idempotent — skips if the account/config already exists.
 */
@Component
public class ManufacturingGlSeeder {

    private static final Logger log = LoggerFactory.getLogger(ManufacturingGlSeeder.class);

    private final ChartOfAccountRepository accounts;
    private final GlConfigRepository       configs;

    public ManufacturingGlSeeder(ChartOfAccountRepository accounts, GlConfigRepository configs) {
        this.accounts = accounts;
        this.configs  = configs;
    }

    @Transactional
    public void seedDefaults(Long companyId) {
        seedAccount(companyId, "1320", "Work In Progress",
                AccountType.ASSET,     GlConfigKey.WIP_INVENTORY);
        seedAccount(companyId, "2350", "WIP Labour Clearing",
                AccountType.LIABILITY, GlConfigKey.LABOUR_APPLIED);
        seedAccount(companyId, "2360", "WIP Overhead Clearing",
                AccountType.LIABILITY, GlConfigKey.OVERHEAD_APPLIED);
        seedAccount(companyId, "5180", "Manufacturing Variance",
                AccountType.EXPENSE,   GlConfigKey.MANUFACTURING_VARIANCE);

        // FINISHED_GOODS → maps to existing 1300 (Inventory); no new account.
        seedConfigOnly(companyId, "1300", GlConfigKey.FINISHED_GOODS);
    }

    private void seedAccount(Long companyId, String code, String name,
                              AccountType type, GlConfigKey configKey) {
        Optional<ChartOfAccount> existing = accounts.findByCompanyIdAndAccountCode(companyId, code);
        ChartOfAccount acct;
        if (existing.isEmpty()) {
            acct = accounts.save(new ChartOfAccount(companyId, code, name, type, null));
            log.info("ManufacturingGlSeeder: seeded account {} '{}' for company {}.",
                    code, name, companyId);
        } else {
            acct = existing.get();
        }
        if (configs.findByCompanyIdAndConfigKey(companyId, configKey).isEmpty()) {
            configs.save(new GlConfig(companyId, configKey, acct.getId(), null));
            log.info("ManufacturingGlSeeder: seeded gl_config {} → {} for company {}.",
                    configKey, code, companyId);
        }
    }

    private void seedConfigOnly(Long companyId, String code, GlConfigKey configKey) {
        if (configs.findByCompanyIdAndConfigKey(companyId, configKey).isPresent()) {
            return;
        }
        accounts.findByCompanyIdAndAccountCode(companyId, code).ifPresent(acct -> {
            configs.save(new GlConfig(companyId, configKey, acct.getId(), null));
            log.info("ManufacturingGlSeeder: seeded gl_config {} → {} for company {}.",
                    configKey, code, companyId);
        });
    }
}
