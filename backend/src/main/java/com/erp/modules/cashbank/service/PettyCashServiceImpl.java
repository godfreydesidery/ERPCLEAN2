package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CreatePettyCashFundRequest;
import com.erp.modules.cashbank.domain.dto.PettyCashFundDto;
import com.erp.modules.cashbank.domain.dto.PettyCashTransactionDto;
import com.erp.modules.cashbank.domain.dto.RecordPettyCashTxnRequest;
import com.erp.modules.cashbank.domain.dto.UpdatePettyCashFundRequest;
import com.erp.modules.cashbank.domain.entity.PettyCashFund;
import com.erp.modules.cashbank.domain.entity.PettyCashTransaction;
import com.erp.modules.cashbank.domain.enums.PettyCashTxnType;
import com.erp.modules.cashbank.repository.PettyCashFundRepository;
import com.erp.modules.cashbank.repository.PettyCashTransactionRepository;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Petty-cash imprest funds + movements (ADR-0050 D-7 PR-B).
 *
 * <p>RECORD-ONLY this slice: no GL leg is posted. {@code gl_account_id} on a disbursement is
 * captured only, for a future manual journal or the GL fast-follow — {@code journal_entry_ref}
 * stays {@code null}.
 */
@Service
@Transactional
public class PettyCashServiceImpl implements PettyCashService {

    private final PettyCashFundRepository        funds;
    private final PettyCashTransactionRepository txns;
    private final CompanyRepository              companies;
    private final BranchRepository               branches;
    private final AppUserRepository              users;
    private final ChartOfAccountRepository       glAccounts;
    private final CashBankNumberGenerator        numbers;
    private final ScopeGuard                     scopeGuard;
    private final AuditService                   audit;

    public PettyCashServiceImpl(PettyCashFundRepository funds,
                                PettyCashTransactionRepository txns,
                                CompanyRepository companies,
                                BranchRepository branches,
                                AppUserRepository users,
                                ChartOfAccountRepository glAccounts,
                                CashBankNumberGenerator numbers,
                                ScopeGuard scopeGuard,
                                AuditService audit) {
        this.funds      = funds;
        this.txns       = txns;
        this.companies  = companies;
        this.branches   = branches;
        this.users      = users;
        this.glAccounts = glAccounts;
        this.numbers    = numbers;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public PettyCashFundDto createFund(CreatePettyCashFundRequest req) {
        Company company = Lookups.orNotFound(companies.findByUid(req.companyUid()), "Company", req.companyUid());
        Long companyId = company.getId();
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String code = (req.code() != null && !req.code().isBlank())
                ? req.code() : numbers.nextPettyCashFund(companyId);
        if (funds.existsByCompanyIdAndCode(companyId, code)) {
            throw new ConflictException("A petty cash fund with this code already exists in this company.");
        }

        Long custodianId = resolveCustodian(companyId, req.custodianUid());
        Long branchId = resolveBranchId(companyId);
        String currency = (req.currency() != null && !req.currency().isBlank())
                ? req.currency() : company.getBaseCurrency();
        BigDecimal floatAmount = req.floatAmount() != null ? req.floatAmount() : BigDecimal.ZERO;

        PettyCashFund fund = new PettyCashFund(companyId, branchId, code, req.name(),
                custodianId, floatAmount, currency, actorId());
        fund = funds.save(fund);

        audit.record(AuditEvent.of(AuditActions.PETTY_CASH_FUND_CREATE, "petty_cash_funds",
                        fund.getId(), fund.getUid())
                .detail(Map.of("code", code, "name", req.name())));

        return toDto(fund);
    }

    @Override
    public PettyCashFundDto updateFund(String uid, UpdatePettyCashFundRequest req) {
        PettyCashFund fund = Lookups.orNotFound(funds.findByUid(uid), "PettyCashFund", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), fund.getCompanyId());

        if (req.name() != null && !req.name().isBlank()) {
            fund.setName(req.name());
        }
        if (req.custodianUid() != null) {
            fund.setCustodianId(req.custodianUid().isBlank()
                    ? null : resolveCustodian(fund.getCompanyId(), req.custodianUid()));
        }
        if (req.floatAmount() != null) {
            fund.setFloatAmount(req.floatAmount());
        }
        if (req.status() != null) {
            fund.setStatus(req.status());
        }
        fund.setUpdatedAt(Instant.now());
        fund.setUpdatedBy(actorId());
        fund = funds.save(fund);

        audit.record(AuditEvent.of(AuditActions.PETTY_CASH_FUND_UPDATE, "petty_cash_funds",
                fund.getId(), fund.getUid()));

        return toDto(fund);
    }

    @Override
    public PettyCashTransactionDto recordTransaction(String fundUid, RecordPettyCashTxnRequest req) {
        PettyCashFund fund = Lookups.orNotFound(funds.findByUid(fundUid), "PettyCashFund", fundUid);
        scopeGuard.assertCanActIn(RequestContext.get(), fund.getCompanyId());

        if (fund.getStatus() != MasterStatus.ACTIVE) {
            throw new ConflictException("This petty cash fund is not active and cannot record transactions.");
        }

        BigDecimal amount = req.amount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            throw new IllegalArgumentException("Amount must not be zero.");
        }
        if (req.type() != PettyCashTxnType.ADJUSTMENT && amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Amount must be positive for this transaction type.");
        }

        BigDecimal newBalance = switch (req.type()) {
            case DISBURSEMENT -> fund.applyDisbursement(amount);
            case REPLENISHMENT -> fund.applyReplenishment(amount);
            case ADJUSTMENT -> fund.applyAdjustment(amount);
        };

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new ConflictException(
                    "This transaction would take the petty cash fund balance below zero.");
        }

        // DISBURSEMENT/REPLENISHMENT: request amount is already validated strictly positive above,
        // so the magnitude and the signed value coincide. ADJUSTMENT: persist the SIGNED request
        // amount as-is (never .abs()) so the ledger can show whether an adjustment increased or
        // decreased the balance on read — balanceAfter already carries the signed effect.
        BigDecimal persistedAmount = req.type() == PettyCashTxnType.ADJUSTMENT ? amount : amount.abs();

        Long glAccountId = resolveGlAccount(fund.getCompanyId(), req.glAccountUid());

        Long actor = actorId();
        String txnNumber = numbers.nextPettyCashTxn(fund.getCompanyId());
        PettyCashTransaction txn = new PettyCashTransaction(fund.getId(), fund.getCompanyId(),
                fund.getBranchId(), txnNumber, req.type(), req.txnDate(), persistedAmount, newBalance,
                glAccountId, req.reference(), req.description(), actor);
        txn = txns.save(txn);

        fund.setUpdatedAt(Instant.now());
        fund.setUpdatedBy(actor);
        funds.save(fund);

        audit.record(AuditEvent.of(auditActionFor(req.type()), "petty_cash_transactions",
                        txn.getId(), txn.getUid())
                .detail(Map.of("txnNumber", txnNumber, "amount", persistedAmount.toPlainString(),
                        "balanceAfter", newBalance.toPlainString())));

        return toDto(txn, fund.getUid());
    }

    @Override
    @Transactional(readOnly = true)
    public PettyCashFundDto getFund(String uid) {
        PettyCashFund fund = Lookups.orNotFound(funds.findByUid(uid), "PettyCashFund", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), fund.getCompanyId());
        return toDto(fund);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PettyCashFundDto> listFunds(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return funds.findByCompanyIdOrderByNameAsc(companyId).stream().map(this::toDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PettyCashTransactionDto> listTransactions(String fundUid) {
        PettyCashFund fund = Lookups.orNotFound(funds.findByUid(fundUid), "PettyCashFund", fundUid);
        scopeGuard.assertCanActIn(RequestContext.get(), fund.getCompanyId());
        return txns.findByPettyCashFundIdOrderByTxnDateDescIdDesc(fund.getId()).stream()
                .map(t -> toDto(t, fund.getUid()))
                .toList();
    }

    // -------------------------------------------------------------------------

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    /**
     * Resolves the branch a new fund attaches to: the caller's active branch (from
     * {@link RequestContext}) — but ONLY when that branch actually belongs to the TARGET company.
     * A root caller may create a fund for a company other than their own active one; in that case
     * the caller's active branch belongs to a DIFFERENT company than {@code companyId} and must
     * never be stamped onto the fund (cross-tenant branch/company mismatch). Falls back to the
     * target company's default branch in that case, or when no branch context is present at all
     * (e.g. a system/seeder call). {@code branch_id} is {@code NOT NULL} on {@code petty_cash_funds}
     * (ADR-0050 D-7.5), so a company with no branch yet cannot receive a fund — create a branch first.
     */
    private Long resolveBranchId(Long companyId) {
        RequestContext.Principal p = RequestContext.get();
        if (p != null && p.branchId() != null && companyId.equals(p.companyId())) {
            return p.branchId();
        }
        return branches.findByCompanyIdAndIsDefaultTrue(companyId)
                .map(Branch::getId)
                .orElseThrow(() -> new ConflictException(
                        "This company has no branch yet; create a branch before adding a petty cash fund."));
    }

    private Long resolveCustodian(Long companyId, String custodianUid) {
        if (custodianUid == null || custodianUid.isBlank()) {
            return null;
        }
        AppUser user = Lookups.orNotFound(users.findByUid(custodianUid), "User", custodianUid);
        if (!users.existsUserInCompany(user.getId(), companyId)) {
            throw NotFoundException.of("User", custodianUid);
        }
        return user.getId();
    }

    private Long resolveGlAccount(Long companyId, String glAccountUid) {
        if (glAccountUid == null || glAccountUid.isBlank()) {
            return null;
        }
        return glAccounts.findByCompanyIdAndUid(companyId, glAccountUid)
                .map(a -> a.getId())
                .orElseThrow(() -> new NotFoundException("GL account not found."));
    }

    private String auditActionFor(PettyCashTxnType type) {
        return switch (type) {
            case DISBURSEMENT -> AuditActions.PETTY_CASH_DISBURSE;
            case REPLENISHMENT -> AuditActions.PETTY_CASH_REPLENISH;
            case ADJUSTMENT -> AuditActions.PETTY_CASH_ADJUST;
        };
    }

    private PettyCashFundDto toDto(PettyCashFund fund) {
        String custodianUid = null;
        String custodianName = null;
        if (fund.getCustodianId() != null) {
            AppUser custodian = users.findScopedById(fund.getCustodianId()).orElse(null);
            if (custodian != null) {
                custodianUid = custodian.getUid();
                custodianName = displayNameOrUsername(custodian);
            }
        }
        return new PettyCashFundDto(fund.getId(), fund.getUid(), fund.getCompanyId(), fund.getBranchId(),
                fund.getCode(), fund.getName(), custodianUid, custodianName,
                fund.getFloatAmount(), fund.getBalanceAmount(), fund.getCurrency().value(),
                fund.getStatus(), fund.getVersion(), fund.getCreatedAt());
    }

    private PettyCashTransactionDto toDto(PettyCashTransaction txn, String fundUid) {
        String glAccountUid = txn.getGlAccountId() != null
                ? glAccounts.findScopedById(txn.getGlAccountId()).map(a -> a.getUid()).orElse(null)
                : null;
        return new PettyCashTransactionDto(txn.getId(), txn.getUid(), fundUid, txn.getTxnNumber(),
                txn.getTxnType(), txn.getTxnDate(), txn.getAmount(), txn.getBalanceAfter(),
                glAccountUid, txn.getReference(), txn.getDescription(), txn.getCreatedAt());
    }

    private String displayNameOrUsername(AppUser user) {
        String displayName = user.getDisplayName();
        return (displayName != null && !displayName.isBlank()) ? displayName : user.getUsername();
    }
}
