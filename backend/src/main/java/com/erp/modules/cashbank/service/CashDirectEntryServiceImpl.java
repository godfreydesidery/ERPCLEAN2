package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CashTransactionDto;
import com.erp.modules.cashbank.domain.dto.RecordDirectEntryRequest;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.entity.CashTransaction;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
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
 * Direct cash/bank entry not tied to AR/AP (ADR-0016 D-4, FR-CASH-09, BR-CASH-05).
 * GL: OUT → DR counter / CR cash-GL; IN → DR cash-GL / CR counter.
 * Source type: CASH_DIRECT.
 */
@Service
@Transactional
public class CashDirectEntryServiceImpl implements CashDirectEntryService {

    private final CashBankAccountRepository  accounts;
    private final CashTransactionRepository  txns;
    private final ChartOfAccountRepository   glAccounts;
    private final CompanyRepository          companies;
    private final CashBankNumberGenerator    numbers;
    private final GLPostingService           glPosting;
    private final ScopeGuard                 scopeGuard;
    private final AuditService               audit;
    private final GLConfigResolver           glConfig;

    public CashDirectEntryServiceImpl(CashBankAccountRepository accounts,
                                       CashTransactionRepository txns,
                                       ChartOfAccountRepository glAccounts,
                                       CompanyRepository companies,
                                       CashBankNumberGenerator numbers,
                                       GLPostingService glPosting,
                                       ScopeGuard scopeGuard,
                                       AuditService audit,
                                       GLConfigResolver glConfig) {
        this.accounts   = accounts;
        this.txns       = txns;
        this.glAccounts = glAccounts;
        this.companies  = companies;
        this.numbers    = numbers;
        this.glPosting  = glPosting;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
        this.glConfig   = glConfig;
    }

    @Override
    public CashTransactionDto recordDirectEntry(RecordDirectEntryRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");

        CashBankAccount account = accounts.findByCompanyIdAndUid(companyId, req.cashBankAccountUid())
                .orElseThrow(() -> new NotFoundException("Cash/bank account not found."));
        // BR-CASH-08: inactive accounts cannot accept direct entries
        if (!account.isActive())
            throw new IllegalStateException("The selected cash/bank account is inactive and cannot accept new entries.");

        ChartOfAccount counterGlAcct = glAccounts.findByCompanyIdAndUid(companyId, req.counterGlAccountUid())
                .orElseThrow(() -> new NotFoundException("Counter GL account not found."));
        if (!counterGlAcct.isActive())
            throw new IllegalStateException("Counter GL account " + counterGlAcct.getAccountCode() + " is inactive.");

        // Issue #2 fix (b): apply the same control-account guard as the manual GL journal path.
        // A cash direct entry is user-driven; posting its counter leg to a control account
        // (AR, AP, INVENTORY, TAX, PAYROLL_CLEARING, FX_CLEARING) would silently corrupt
        // the matching sub-ledger — exactly the same risk as a manual GL journal (ADR-0013 D-3).
        // CASH/BANK control accounts are deliberately exempt (blocksManualPosting()==false) because
        // bank charges and interest are the primary use-case for this endpoint.
        if (!counterGlAcct.isAllowManualPosting()) {
            throw new com.erp.platform.common.api.ConflictException(
                    "Counter GL account " + counterGlAcct.getAccountCode()
                            + " does not allow manual posting and cannot be used as a cash-entry "
                            + "counter account. Choose a non-control account.");
        }
        if (counterGlAcct.getControlType() != null
                && counterGlAcct.getControlType().blocksManualPosting()) {
            // ADR-0013 D-3: control accounts (AR, AP, inventory, tax, payroll clearing, FX clearing)
            // cannot be targeted by a direct cash entry — doing so would silently corrupt the sub-ledger
            throw new com.erp.platform.common.api.ConflictException(
                    "The selected counter account (" + counterGlAcct.getAccountCode()
                            + ") is a system-controlled account and cannot be used as a counter account "
                            + "for a direct cash entry. Please choose a regular income or expense account.");
        }

        // ACC-13 / PAR-08: optional input VAT inside a payment.
        BigDecimal vat = req.vatAmount() != null ? req.vatAmount() : BigDecimal.ZERO;
        if (vat.signum() > 0) {
            if (req.direction() != CashTxnDirection.OUT) {
                throw new IllegalArgumentException(
                        "Input VAT can only be claimed on money paid out.");
            }
            if (vat.compareTo(req.amount()) >= 0) {
                throw new IllegalArgumentException(
                        "The VAT must be less than the amount paid.");
            }
        }

        return persistAndPost(companyId, currency, account, counterGlAcct, req, branchId(), vat);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The counter account here is resolved by the calling module from its own gl_config (payroll's
     * NET_WAGES_PAYABLE), not picked by a user — so the two counter-account guards above, which
     * exist to stop a USER from corrupting a sub-ledger through this endpoint, do not apply. They
     * would in fact refuse every legitimate settlement of a control account: 2550 Net Wages Payable
     * is PAYROLL_CLEARING with allowManualPosting=false on every provisioned company, which is how
     * payroll disbursement could never reach PAID. The GL engine itself never applied the manual-
     * posting gate to a CASH_DIRECT entry; only this service-level copy did. Everything else —
     * tenancy, active accounts, period gate, balanced posting — is identical to the user path.
     */
    @Override
    public CashTransactionDto recordSystemEntry(RecordDirectEntryRequest req, Long branchId) {
        Company company = companies.findByUid(req.companyUid())
                .orElseThrow(() -> new NotFoundException("Company not found."));
        Long companyId = company.getId();
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        String currency = company.getBaseCurrency() != null ? company.getBaseCurrency() : "TZS";

        CashBankAccount account = accounts.findByCompanyIdAndUid(companyId, req.cashBankAccountUid())
                .orElseThrow(() -> new NotFoundException("Cash/bank account not found."));
        if (!account.isActive())
            throw new IllegalStateException("The selected cash/bank account is inactive and cannot accept new entries.");

        ChartOfAccount counterGlAcct = glAccounts.findByCompanyIdAndUid(companyId, req.counterGlAccountUid())
                .orElseThrow(() -> new NotFoundException("Counter GL account not found."));
        if (!counterGlAcct.isActive())
            throw new IllegalStateException("Counter GL account " + counterGlAcct.getAccountCode() + " is inactive.");

        return persistAndPost(companyId, currency, account, counterGlAcct, req,
                branchId != null ? branchId : branchId(), BigDecimal.ZERO);
    }

    private CashTransactionDto persistAndPost(Long companyId, String currency, CashBankAccount account,
                                              ChartOfAccount counterGlAcct, RecordDirectEntryRequest req,
                                              Long branchId, BigDecimal vat) {
        Long actor    = actorId();
        String txnNumber = numbers.nextTransaction(companyId);

        // 1. Insert the cash_transaction
        CashTransaction txn = new CashTransaction(
                companyId, branchId, account.getId(), txnNumber, req.txnDate(),
                req.direction(), req.amount(), currency,
                CashTxnType.DIRECT_ENTRY, null, counterGlAcct.getId(),
                req.memo(), actor);
        txn = txns.save(txn);

        // 2. Post the balanced GL entry (D-4):
        //    OUT (e.g. bank charge): DR counter / CR cash-GL
        //    IN  (e.g. interest):    DR cash-GL / CR counter
        //    OUT with input VAT (ACC-13): DR counter (amount − VAT) + DR VAT Input (VAT) / CR cash-GL
        List<LineDraft> lines = new java.util.ArrayList<>();
        if (req.direction() == CashTxnDirection.OUT) {
            lines.add(new LineDraft(counterGlAcct.getId(), req.amount().subtract(vat), BigDecimal.ZERO,
                    currency, "Direct entry out — " + txnNumber));
            if (vat.signum() > 0) {
                lines.add(new LineDraft(glConfig.resolve(companyId, GlConfigKey.VAT_INPUT).getId(),
                        vat, BigDecimal.ZERO, currency, "Input VAT — " + txnNumber));
            }
            lines.add(new LineDraft(account.getGlAccountId(), BigDecimal.ZERO, req.amount(),
                    currency, "Cash out — " + txnNumber));
        } else {
            lines.add(new LineDraft(account.getGlAccountId(), req.amount(), BigDecimal.ZERO,
                    currency, "Cash in — " + txnNumber));
            lines.add(new LineDraft(counterGlAcct.getId(), BigDecimal.ZERO, req.amount(),
                    currency, "Direct entry in — " + txnNumber));
        }

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, branchId, req.txnDate(),
                "Cash Direct Entry " + txnNumber,
                JournalSourceType.CASH_DIRECT,
                txn.getUid(), null, actor,
                lines);
        JournalEntryDto posted = glPosting.post(draft);

        txn.setJournalEntryRef(posted.uid());
        txn.setUpdatedAt(Instant.now());
        txn.setUpdatedBy(actor);
        txn = txns.save(txn);

        audit.record(AuditEvent.of(AuditActions.CASH_ENTRY_RECORD, "cash_transactions",
                        txn.getId(), txn.getUid())
                .detail(Map.of(
                        "txnNumber", txnNumber,
                        "direction", req.direction().name(),
                        "amount",    req.amount().toPlainString(),
                        "vatAmount", vat.toPlainString(),
                        "glEntryUid", posted.uid())));

        return toDto(txn);
    }

    @Override
    @Transactional(readOnly = true)
    public CashTransactionDto getByUid(String uid) {
        CashTransaction txn = Lookups.orNotFound(txns.findByUid(uid), "CashTransaction", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), txn.getCompanyId());
        return toDto(txn);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CashTransactionDto> listByAccount(Long companyId, Long accountId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        // Ownership check: verify the account belongs to companyId before returning its ledger.
        // Without this, a caller may pass their own companyId (satisfying the scope guard above)
        // but a foreign accountId and read another company's cash transactions (confused-deputy).
        accounts.findById(accountId)
                .filter(a -> a.getCompanyId().equals(companyId))
                .orElseThrow(() -> new NotFoundException("Cash/bank account not found."));
        return txns.findByCashBankAccountIdOrderByTxnDateAscIdAsc(accountId)
                .stream().map(CashDirectEntryServiceImpl::toDto).toList();
    }

    // -------------------------------------------------------------------------

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private Long branchId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.branchId() : null;
    }

    static CashTransactionDto toDto(CashTransaction t) {
        return new CashTransactionDto(
                t.getId(), t.getUid(), t.getCompanyId(), t.getCashBankAccountId(),
                t.getTxnNumber(), t.getTxnDate(),
                // P2: value date + linked cheque
                t.getValueDate(), t.getChequeId(),
                t.getDirection(), t.getAmount(),
                t.getCurrency().value(), t.getTxnType(), t.getSourceRef(),
                t.getCounterGlAccountId(), t.getJournalEntryRef(),
                t.isCleared(), t.getClearedInReconciliationId(), t.getMemo());
    }
}
