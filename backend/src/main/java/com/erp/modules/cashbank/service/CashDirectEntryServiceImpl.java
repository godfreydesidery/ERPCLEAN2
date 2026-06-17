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
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.service.GLPostingService;
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

    public CashDirectEntryServiceImpl(CashBankAccountRepository accounts,
                                       CashTransactionRepository txns,
                                       ChartOfAccountRepository glAccounts,
                                       CompanyRepository companies,
                                       CashBankNumberGenerator numbers,
                                       GLPostingService glPosting,
                                       ScopeGuard scopeGuard,
                                       AuditService audit) {
        this.accounts   = accounts;
        this.txns       = txns;
        this.glAccounts = glAccounts;
        this.companies  = companies;
        this.numbers    = numbers;
        this.glPosting  = glPosting;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public CashTransactionDto recordDirectEntry(RecordDirectEntryRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company: " + req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");

        CashBankAccount account = accounts.findByCompanyIdAndUid(companyId, req.cashBankAccountUid())
                .orElseThrow(() -> new NotFoundException("Cash/bank account: " + req.cashBankAccountUid()));
        if (!account.isActive())
            throw new IllegalStateException("Cash/bank account is inactive (BR-CASH-08).");

        ChartOfAccount counterGlAcct = glAccounts.findByCompanyIdAndUid(companyId, req.counterGlAccountUid())
                .orElseThrow(() -> new NotFoundException("Counter GL account: " + req.counterGlAccountUid()));
        if (!counterGlAcct.isActive())
            throw new IllegalStateException("Counter GL account " + counterGlAcct.getAccountCode() + " is inactive.");

        Long branchId = branchId();
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
        LineDraft debitLine;
        LineDraft creditLine;
        if (req.direction() == CashTxnDirection.OUT) {
            debitLine  = new LineDraft(counterGlAcct.getId(), req.amount(), BigDecimal.ZERO,
                    currency, "Direct entry out — " + txnNumber);
            creditLine = new LineDraft(account.getGlAccountId(), BigDecimal.ZERO, req.amount(),
                    currency, "Cash out — " + txnNumber);
        } else {
            debitLine  = new LineDraft(account.getGlAccountId(), req.amount(), BigDecimal.ZERO,
                    currency, "Cash in — " + txnNumber);
            creditLine = new LineDraft(counterGlAcct.getId(), BigDecimal.ZERO, req.amount(),
                    currency, "Direct entry in — " + txnNumber);
        }

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, branchId, req.txnDate(),
                "Cash Direct Entry " + txnNumber,
                JournalSourceType.CASH_DIRECT,
                txn.getUid(), null, actor,
                List.of(debitLine, creditLine));
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
                t.getTxnNumber(), t.getTxnDate(), t.getDirection(), t.getAmount(),
                t.getCurrency().value(), t.getTxnType(), t.getSourceRef(),
                t.getCounterGlAccountId(), t.getJournalEntryRef(),
                t.isCleared(), t.getClearedInReconciliationId(), t.getMemo());
    }
}
