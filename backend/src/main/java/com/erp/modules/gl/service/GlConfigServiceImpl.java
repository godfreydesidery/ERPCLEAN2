package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.GlConfigDto;
import com.erp.modules.gl.domain.dto.SetGlConfigRequest;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.GlConfig;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.GlConfigRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class GlConfigServiceImpl implements GlConfigService {

    private static final Logger log = LoggerFactory.getLogger(GlConfigServiceImpl.class);

    /** Keys seeded by default (ROADMAP T1.1, ADR-0013 D-15). */
    private static final Map<GlConfigKey, String> DEFAULT_MAPPINGS = Map.ofEntries(
            Map.entry(GlConfigKey.SALES_REVENUE,       "4100"),
            Map.entry(GlConfigKey.VAT_PAYABLE,         "2200"),
            Map.entry(GlConfigKey.ACCOUNTS_RECEIVABLE, "1200"),
            Map.entry(GlConfigKey.CASH,                "1000"),
            Map.entry(GlConfigKey.INVENTORY,           "1300"),
            Map.entry(GlConfigKey.COGS,                "5100"),
            Map.entry(GlConfigKey.ACCOUNTS_PAYABLE,    "2100"),
            // VAT/Tax increment (ADR-0017 D-5)
            Map.entry(GlConfigKey.VAT_INPUT,           "1400"),
            Map.entry(GlConfigKey.VAT_DUE,             "2300"),
            Map.entry(GlConfigKey.WHT_PAYABLE,         "2400"),
            Map.entry(GlConfigKey.WHT_RECEIVABLE,      "1500"),
            // Year-End Close increment (ADR-0019 D-9) — 3900 is in the V10 CoA default seed
            Map.entry(GlConfigKey.RETAINED_EARNINGS,   "3900"),
            // Inventory Valuation & COGS increment (ADR-0020 D-8)
            Map.entry(GlConfigKey.GRNI,                "2150"),
            Map.entry(GlConfigKey.STOCK_ADJUSTMENT,    "5160")
    );

    private final GlConfigRepository configs;
    private final ChartOfAccountRepository accounts;
    private final CompanyRepository companies;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public GlConfigServiceImpl(GlConfigRepository configs,
                                ChartOfAccountRepository accounts,
                                CompanyRepository companies,
                                ScopeGuard scopeGuard,
                                AuditService audit) {
        this.configs    = configs;
        this.accounts   = accounts;
        this.companies  = companies;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public GlConfigDto set(SetGlConfigRequest req) {
        Company company = requireCompanyByUid(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), company.getId());

        ChartOfAccount account = accounts.findByCompanyIdAndUid(company.getId(), req.accountUid())
                .orElseThrow(() -> NotFoundException.of("ChartOfAccount", req.accountUid()));

        Long actorId = actorId();
        GlConfig config = configs.findByCompanyIdAndConfigKey(company.getId(), req.configKey())
                .orElse(null);

        if (config == null) {
            config = new GlConfig(company.getId(), req.configKey(), account.getId(), actorId);
        } else {
            config.setAccountId(account.getId());
            config.setUpdatedAt(Instant.now());
            config.setUpdatedBy(actorId);
        }
        config = configs.save(config);

        audit.record(AuditEvent.of(AuditActions.GL_CONFIG_SET, "gl_configs",
                        config.getId(), config.getUid())
                .detail(Map.of("key", req.configKey().name(), "accountCode", account.getAccountCode())));

        return toDto(config, account);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GlConfigDto> list(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return configs.findByCompanyId(companyId).stream()
                .map(c -> {
                    ChartOfAccount acct = accounts.findById(c.getAccountId()).orElse(null);
                    return toDto(c, acct);
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public GlConfigDto getByUid(String uid) {
        GlConfig config = configs.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("GlConfig", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), config.getCompanyId());
        ChartOfAccount acct = accounts.findById(config.getAccountId()).orElse(null);
        return toDto(config, acct);
    }

    @Override
    public void seedDefaults(Long companyId) {
        int seeded = 0;
        for (Map.Entry<GlConfigKey, String> entry : DEFAULT_MAPPINGS.entrySet()) {
            GlConfigKey key  = entry.getKey();
            String accountCode = entry.getValue();
            if (configs.findByCompanyIdAndConfigKey(companyId, key).isPresent()) {
                continue;
            }
            Optional<ChartOfAccount> acctOpt =
                    accounts.findByCompanyIdAndAccountCode(companyId, accountCode);
            if (acctOpt.isEmpty()) {
                log.warn("Cannot seed gl_configs[{}] for company {}: account code {} not found.",
                        key, companyId, accountCode);
                continue;
            }
            configs.save(new GlConfig(companyId, key, acctOpt.get().getId(), null));
            seeded++;
        }
        if (seeded > 0) {
            log.info("Seeded {} gl_configs entry/entries for company {}.", seeded, companyId);
        }
    }

    // -------------------------------------------------------------------------

    private Company requireCompanyByUid(String uid) {
        return companies.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("Company", uid));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private static GlConfigDto toDto(GlConfig c, ChartOfAccount acct) {
        return new GlConfigDto(
                c.getId(), c.getUid(), c.getCompanyId(), c.getConfigKey(),
                c.getAccountId(),
                acct != null ? acct.getAccountCode() : null,
                acct != null ? acct.getName() : null);
    }
}
