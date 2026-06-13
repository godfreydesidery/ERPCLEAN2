package com.erp.modules.hr.service;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.GlConfig;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.GlConfigRepository;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds HR GL accounts (5700, 5710, 2500-2550, 1450) and their gl_configs mapping for a new company
 * (ADR-0032 D-8, BootstrapRunner pattern). Idempotent — skips if already present.
 */
@Component
public class HrGlSeeder {

    private static final Logger log = LoggerFactory.getLogger(HrGlSeeder.class);

    private static final List<Object[]> HR_ACCOUNTS = List.of(
            new Object[]{"5700", "Salaries & Wages Expense",         AccountType.EXPENSE,  GlConfigKey.SALARY_EXPENSE},
            new Object[]{"5710", "Employer Statutory Contributions",  AccountType.EXPENSE,  GlConfigKey.EMPLOYER_STATUTORY_EXPENSE},
            new Object[]{"2500", "PAYE Payable",                      AccountType.LIABILITY, GlConfigKey.PAYE_PAYABLE},
            new Object[]{"2510", "NSSF Payable",                      AccountType.LIABILITY, GlConfigKey.NSSF_PAYABLE},
            new Object[]{"2520", "WCF Payable",                       AccountType.LIABILITY, GlConfigKey.WCF_PAYABLE},
            new Object[]{"2530", "SDL Payable",                       AccountType.LIABILITY, GlConfigKey.SDL_PAYABLE},
            new Object[]{"2540", "HESLB Payable",                     AccountType.LIABILITY, GlConfigKey.HESLB_PAYABLE},
            new Object[]{"2550", "Net Wages Payable",                  AccountType.LIABILITY, GlConfigKey.NET_WAGES_PAYABLE},
            new Object[]{"1450", "Employee Loans Receivable",          AccountType.ASSET,     GlConfigKey.EMPLOYEE_LOAN_RECEIVABLE}
    );

    private final ChartOfAccountRepository accounts;
    private final GlConfigRepository       configs;

    public HrGlSeeder(ChartOfAccountRepository accounts, GlConfigRepository configs) {
        this.accounts = accounts;
        this.configs  = configs;
    }

    @Transactional
    public void seedDefaults(Long companyId) {
        for (Object[] row : HR_ACCOUNTS) {
            String code           = (String) row[0];
            String name           = (String) row[1];
            AccountType type      = (AccountType) row[2];
            GlConfigKey configKey = (GlConfigKey) row[3];
            seedAccount(companyId, code, name, type, configKey);
        }
    }

    private void seedAccount(Long companyId, String code, String name,
                              AccountType type, GlConfigKey configKey) {
        Optional<ChartOfAccount> existing = accounts.findByCompanyIdAndAccountCode(companyId, code);
        ChartOfAccount acct;
        if (existing.isEmpty()) {
            acct = accounts.save(new ChartOfAccount(companyId, code, name, type, null));
            log.info("HrGlSeeder: seeded account {} '{}' for company {}.", code, name, companyId);
        } else {
            acct = existing.get();
        }
        if (configs.findByCompanyIdAndConfigKey(companyId, configKey).isEmpty()) {
            configs.save(new GlConfig(companyId, configKey, acct.getId(), null));
            log.info("HrGlSeeder: seeded gl_config {} → {} for company {}.", configKey, code, companyId);
        }
    }
}
