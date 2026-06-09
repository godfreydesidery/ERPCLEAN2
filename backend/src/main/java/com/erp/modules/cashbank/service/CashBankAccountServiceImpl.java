package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CashBankAccountDto;
import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.dto.UpdateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD + default management for cash/bank accounts (ADR-0016 D-1, FR-CASH-01..04).
 * Service-enforces: BANK requires bank_name; CASH forbids bank details; GL link is 1:1 per company;
 * account with transactions cannot be deleted (BR-CASH-13).
 */
@Service
@Transactional
public class CashBankAccountServiceImpl implements CashBankAccountService {

    private final CashBankAccountRepository accounts;
    private final ChartOfAccountRepository  glAccounts;
    private final CompanyRepository         companies;
    private final BranchRepository          branches;
    private final CashBankNumberGenerator   numbers;
    private final ScopeGuard                scopeGuard;
    private final AuditService              audit;

    public CashBankAccountServiceImpl(CashBankAccountRepository accounts,
                                       ChartOfAccountRepository glAccounts,
                                       CompanyRepository companies,
                                       BranchRepository branches,
                                       CashBankNumberGenerator numbers,
                                       ScopeGuard scopeGuard,
                                       AuditService audit) {
        this.accounts   = accounts;
        this.glAccounts = glAccounts;
        this.companies  = companies;
        this.branches   = branches;
        this.numbers    = numbers;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public CashBankAccountDto create(CreateCashBankAccountRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company: " + req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElse("TZS");

        // Resolve branch
        Long branchId = null;
        if (req.branchUid() != null && !req.branchUid().isBlank()) {
            branchId = branches.findByUid(req.branchUid())
                    .map(b -> b.getId())
                    .orElseThrow(() -> new NotFoundException("Branch: " + req.branchUid()));
        }

        // Validate bank-details rule (FR-CASH-01, chk_cash_bank_account_bank_details)
        if (req.accountType() == CashBankAccountType.BANK && (req.bankName() == null || req.bankName().isBlank())) {
            throw new IllegalArgumentException("BANK account requires bankName (FR-CASH-01).");
        }
        if (req.accountType() == CashBankAccountType.CASH && req.bankName() != null && !req.bankName().isBlank()) {
            throw new IllegalArgumentException("CASH account must not have bank details (FR-CASH-01).");
        }

        // Resolve the linked GL account
        ChartOfAccount glAcct = glAccounts.findByCompanyIdAndUid(companyId, req.glAccountUid())
                .orElseThrow(() -> new NotFoundException("GL account: " + req.glAccountUid()));
        if (!glAcct.isActive()) {
            throw new IllegalStateException("GL account " + glAcct.getAccountCode() + " is inactive.");
        }

        // 1:1 uniqueness check (uq_cash_bank_account_gl enforces at DB, but fail early with a clear msg)
        if (accounts.findByCompanyIdAndGlAccountId(companyId, glAcct.getId()).isPresent()) {
            throw new ConflictException("GL account " + glAcct.getAccountCode()
                    + " is already linked to another cash/bank account in this company.");
        }

        // Clear existing default if setAsDefault. saveAndFlush so the clear lands before the new
        // account's is_default=true insert (partial-unique uq_cash_bank_account_default, see setDefault).
        if (req.setAsDefault()) {
            Long actor = actorId();
            accounts.findByCompanyIdAndIsDefaultTrue(companyId)
                    .ifPresent(existing -> {
                        existing.setDefault(false);
                        existing.setUpdatedAt(Instant.now());
                        existing.setUpdatedBy(actor);
                        accounts.saveAndFlush(existing);
                    });
        }

        String code = numbers.nextAccount(companyId);
        CashBankAccount account = new CashBankAccount(
                companyId, branchId, code, req.name(),
                req.accountType(), currency, glAcct.getId(), req.setAsDefault(), actorId());
        account.setBankName(req.bankName());
        account.setBankAccountNo(req.bankAccountNo());
        account.setBankBranch(req.bankBranch());
        account = accounts.save(account);

        audit.record(AuditEvent.of(AuditActions.CASH_ACCOUNT_CREATE, "cash_bank_accounts",
                        account.getId(), account.getUid())
                .detail(Map.of("code", code, "name", req.name(), "type", req.accountType().name())));

        return toDto(account);
    }

    @Override
    public CashBankAccountDto update(String uid, UpdateCashBankAccountRequest req) {
        CashBankAccount account = Lookups.orNotFound(accounts.findByUid(uid), "CashBankAccount", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), account.getCompanyId());

        if (req.name() != null && !req.name().isBlank()) {
            account.setName(req.name());
        }
        if (account.getAccountType() == CashBankAccountType.BANK) {
            if (req.bankName() != null) account.setBankName(req.bankName());
            if (req.bankAccountNo() != null) account.setBankAccountNo(req.bankAccountNo());
            if (req.bankBranch() != null) account.setBankBranch(req.bankBranch());
        }
        if (req.active() != null) {
            // Cannot delete if has transactions — deactivate only (BR-CASH-13)
            if (Boolean.FALSE.equals(req.active()) && accounts.hasTransactions(account.getId())) {
                account.setActive(false); // allowed — deactivation preserves history
            } else if (req.active() != null) {
                account.setActive(req.active());
            }
        }
        account.setUpdatedAt(Instant.now());
        account.setUpdatedBy(actorId());
        account = accounts.save(account);

        audit.record(AuditEvent.of(AuditActions.CASH_ACCOUNT_UPDATE, "cash_bank_accounts",
                        account.getId(), account.getUid())
                .detail(Map.of("uid", uid)));

        return toDto(account);
    }

    @Override
    public CashBankAccountDto setDefault(String uid) {
        CashBankAccount account = Lookups.orNotFound(accounts.findByUid(uid), "CashBankAccount", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), account.getCompanyId());

        // Clear existing default for this company. saveAndFlush so the clear (is_default=false)
        // hits the DB BEFORE the set below — the partial-unique uq_cash_bank_account_default fires
        // row-by-row in Postgres, and Hibernate would otherwise batch/flush the set-true before the
        // clear, causing a spurious unique violation (mirrors the GL set-primary-route fix).
        Long actor = actorId();
        Long accountId = account.getId();
        Long accountCompanyId = account.getCompanyId();
        accounts.findByCompanyIdAndIsDefaultTrue(accountCompanyId)
                .ifPresent(existing -> {
                    if (!existing.getId().equals(accountId)) {
                        existing.setDefault(false);
                        existing.setUpdatedAt(Instant.now());
                        existing.setUpdatedBy(actor);
                        accounts.saveAndFlush(existing);
                    }
                });

        account.setDefault(true);
        account.setUpdatedAt(Instant.now());
        account.setUpdatedBy(actorId());
        account = accounts.save(account);

        audit.record(AuditEvent.of(AuditActions.CASH_ACCOUNT_UPDATE, "cash_bank_accounts",
                        account.getId(), account.getUid())
                .detail(Map.of("action", "setDefault")));

        return toDto(account);
    }

    @Override
    @Transactional(readOnly = true)
    public CashBankAccountDto getByUid(String uid) {
        CashBankAccount account = Lookups.orNotFound(accounts.findByUid(uid), "CashBankAccount", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), account.getCompanyId());
        return toDto(account);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CashBankAccountDto> listByCompany(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return accounts.findByCompanyId(companyId).stream()
                .map(CashBankAccountServiceImpl::toDto).toList();
    }

    // -------------------------------------------------------------------------

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    static CashBankAccountDto toDto(CashBankAccount a) {
        return new CashBankAccountDto(
                a.getId(), a.getUid(), a.getCompanyId(), a.getBranchId(),
                a.getCode(), a.getName(), a.getAccountType(),
                a.getBankName(), a.getBankAccountNo(), a.getBankBranch(),
                a.getCurrency(), a.getGlAccountId(), a.isDefault(), a.isActive());
    }
}
