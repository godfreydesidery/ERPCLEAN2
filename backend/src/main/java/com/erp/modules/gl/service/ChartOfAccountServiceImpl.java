package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.AccountDto;
import com.erp.modules.gl.domain.dto.CreateAccountRequest;
import com.erp.modules.gl.domain.dto.UpdateAccountRequest;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.NormalBalance;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ChartOfAccountServiceImpl implements ChartOfAccountService {

    private static final Logger log = LoggerFactory.getLogger(ChartOfAccountServiceImpl.class);

    private final ChartOfAccountRepository accounts;
    private final CompanyRepository companies;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    /** Standard TZ CoA seed: (code, name, type). */
    private static final List<Object[]> DEFAULT_ACCOUNTS = List.of(
            new Object[]{"1000", "Cash",                        AccountType.ASSET},
            new Object[]{"1100", "Bank",                        AccountType.ASSET},
            new Object[]{"1200", "Accounts Receivable",         AccountType.ASSET},
            new Object[]{"1300", "Inventory",                   AccountType.ASSET},
            new Object[]{"1400", "VAT Input (Recoverable)",     AccountType.ASSET},
            new Object[]{"1500", "WHT Receivable",              AccountType.ASSET},
            new Object[]{"2100", "Accounts Payable",            AccountType.LIABILITY},
            new Object[]{"2200", "VAT Payable",                 AccountType.LIABILITY},
            new Object[]{"2300", "VAT Due (Payable to TRA)",    AccountType.LIABILITY},
            new Object[]{"2400", "WHT Payable (to TRA)",        AccountType.LIABILITY},
            new Object[]{"3000", "Owner's Equity / Capital",    AccountType.EQUITY},
            new Object[]{"3900", "Retained Earnings",           AccountType.EQUITY},
            new Object[]{"4100", "Sales Revenue",               AccountType.INCOME},
            new Object[]{"5100", "Cost of Goods Sold",          AccountType.EXPENSE},
            new Object[]{"5200", "Rent Expense",                AccountType.EXPENSE},
            new Object[]{"5300", "Salaries & Wages",            AccountType.EXPENSE},
            new Object[]{"5400", "Utilities",                   AccountType.EXPENSE},
            // Inventory Valuation & COGS increment (ADR-0020 D-8)
            new Object[]{"2150", "Goods Received Not Invoiced", AccountType.LIABILITY},
            new Object[]{"5160", "Stock Adjustment / Shrinkage",AccountType.EXPENSE},
            // procurement-depth (ADR-0027 D-9)
            new Object[]{"2160", "Landed Cost Clearing",        AccountType.LIABILITY},
            // sales-depth (ADR-0029 D-4) — POS over/short variance accounts
            new Object[]{"4900", "Cash Over (Till Surplus)",    AccountType.INCOME},
            new Object[]{"5170", "Cash Short / Till Shortage",  AccountType.EXPENSE},
            // HR & Payroll increment (ADR-0032 D-8)
            new Object[]{"5700", "Salaries & Wages Expense",            AccountType.EXPENSE},
            new Object[]{"5710", "Employer Statutory Contributions",     AccountType.EXPENSE},
            new Object[]{"2500", "PAYE Payable",                         AccountType.LIABILITY},
            new Object[]{"2510", "NSSF Payable",                         AccountType.LIABILITY},
            new Object[]{"2520", "WCF Payable",                          AccountType.LIABILITY},
            new Object[]{"2530", "SDL Payable",                          AccountType.LIABILITY},
            new Object[]{"2540", "HESLB Payable",                        AccountType.LIABILITY},
            new Object[]{"2550", "Net Wages Payable",                    AccountType.LIABILITY},
            new Object[]{"1450", "Employee Loans Receivable",            AccountType.ASSET},
            // Manufacturing / Production increment (ADR-0035 D-7)
            new Object[]{"1320", "Work In Progress",                     AccountType.ASSET},
            new Object[]{"2350", "WIP Labour Clearing",                  AccountType.LIABILITY},
            new Object[]{"2360", "WIP Overhead Clearing",                AccountType.LIABILITY},
            new Object[]{"5180", "Manufacturing Variance",               AccountType.EXPENSE},
            // FX / Multi-currency increment (ADR-0036 D-5/D-6/D-10, V79): realized + unrealized FX gain/loss
            new Object[]{"4910", "Unrealized FX Gain",                   AccountType.INCOME},
            new Object[]{"4920", "Realized FX Gain",                     AccountType.INCOME},
            new Object[]{"5190", "Realized FX Loss",                     AccountType.EXPENSE},
            new Object[]{"5191", "Unrealized FX Loss",                   AccountType.EXPENSE}
    );

    public ChartOfAccountServiceImpl(ChartOfAccountRepository accounts,
                                      CompanyRepository companies,
                                      ScopeGuard scopeGuard,
                                      AuditService audit) {
        this.accounts   = accounts;
        this.companies  = companies;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public AccountDto create(CreateAccountRequest req) {
        Company company = requireCompanyByUid(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), company.getId());

        if (accounts.existsByCompanyIdAndAccountCode(company.getId(), req.accountCode())) {
            throw new ConflictException(
                    "Account code " + req.accountCode() + " already exists in this company.");
        }

        ChartOfAccount account = new ChartOfAccount(
                company.getId(), req.accountCode(), req.name(), req.accountType(), actorId());
        account = accounts.save(account);

        audit.record(AuditEvent.of(AuditActions.GL_ACCOUNT_CREATE, "chart_of_accounts",
                        account.getId(), account.getUid())
                .detail(Map.of("code", req.accountCode(), "type", req.accountType().name())));

        return toDto(account);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountDto getByUid(String uid) {
        ChartOfAccount account = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), account.getCompanyId());
        return toDto(account);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AccountDto> list(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return accounts.findByCompanyId(companyId, pageable).map(ChartOfAccountServiceImpl::toDto);
    }

    @Override
    public AccountDto update(String uid, UpdateAccountRequest req) {
        ChartOfAccount account = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), account.getCompanyId());

        if (req.name() != null)        account.setName(req.name());
        if (req.accountType() != null) {
            account.setAccountType(req.accountType());
            account.setNormalBalance(NormalBalance.forType(req.accountType()));
        }
        if (req.active() != null)               account.setActive(req.active());
        if (req.allowManualPosting() != null)   account.setAllowManualPosting(req.allowManualPosting());
        account.setUpdatedAt(Instant.now());
        account.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.GL_ACCOUNT_UPDATE, "chart_of_accounts",
                        account.getId(), account.getUid())
                .detail(Map.of("uid", uid)));

        return toDto(account);
    }

    @Override
    public AccountDto deactivate(String uid) {
        ChartOfAccount account = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), account.getCompanyId());

        account.setActive(false);
        account.setStatus(MasterStatus.INACTIVE);
        account.setUpdatedAt(Instant.now());
        account.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.GL_ACCOUNT_DEACTIVATE, "chart_of_accounts",
                        account.getId(), account.getUid())
                .detail(Map.of("code", account.getAccountCode())));

        return toDto(account);
    }

    @Override
    public void delete(String uid) {
        ChartOfAccount account = requireByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), account.getCompanyId());

        if (accounts.hasPostings(account.getId())) {
            throw new ConflictException(
                    "Account " + account.getAccountCode()
                            + " has postings and cannot be deleted (BR-GL-07). Deactivate it instead.");
        }
        accounts.delete(account);
    }

    @Override
    public void seedDefaults(Long companyId) {
        int seeded = 0;
        for (Object[] row : DEFAULT_ACCOUNTS) {
            String code = (String) row[0];
            String name = (String) row[1];
            AccountType type = (AccountType) row[2];
            if (!accounts.existsByCompanyIdAndAccountCode(companyId, code)) {
                accounts.save(new ChartOfAccount(companyId, code, name, type, null));
                seeded++;
            }
        }
        if (seeded > 0) {
            log.info("Seeded {} GL account(s) for company {}.", seeded, companyId);
        }
    }

    // -------------------------------------------------------------------------

    private ChartOfAccount requireByUid(String uid) {
        return accounts.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("ChartOfAccount", uid));
    }

    private Company requireCompanyByUid(String uid) {
        return companies.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("Company", uid));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    static AccountDto toDto(ChartOfAccount a) {
        return new AccountDto(
                a.getId(), a.getUid(), a.getCompanyId(),
                a.getAccountCode(), a.getName(),
                a.getAccountType(), a.getNormalBalance(),
                a.isActive(), a.isAllowManualPosting(), a.getStatus().name());
    }
}
