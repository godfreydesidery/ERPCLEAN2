package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.BankReconciliationDto;
import com.erp.modules.cashbank.domain.dto.MarkClearedRequest;
import com.erp.modules.cashbank.domain.dto.OpenReconciliationRequest;
import com.erp.modules.cashbank.domain.entity.BankReconciliation;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.entity.CashTransaction;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.domain.enums.ReconciliationStatus;
import com.erp.modules.cashbank.repository.BankReconciliationRepository;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
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
 * Bank reconciliation lifecycle: DRAFT → COMPLETED.
 * Invariants:
 *   - BANK accounts only (FR-CASH-13, BR-CASH-11).
 *   - Completion: clearedBookBalance must equal statementClosingBalance (BigDecimal.compareTo, BR-CASH-06).
 *   - Once COMPLETED, cleared flags are immutable (BR-CASH-07).
 */
@Service
@Transactional
public class BankReconciliationServiceImpl implements BankReconciliationService {

    private final BankReconciliationRepository reconciliations;
    private final CashBankAccountRepository    accounts;
    private final CashTransactionRepository    txns;
    private final CompanyRepository            companies;
    private final CashBankNumberGenerator      numbers;
    private final ScopeGuard                   scopeGuard;
    private final AuditService                 audit;

    public BankReconciliationServiceImpl(BankReconciliationRepository reconciliations,
                                          CashBankAccountRepository accounts,
                                          CashTransactionRepository txns,
                                          CompanyRepository companies,
                                          CashBankNumberGenerator numbers,
                                          ScopeGuard scopeGuard,
                                          AuditService audit) {
        this.reconciliations = reconciliations;
        this.accounts        = accounts;
        this.txns            = txns;
        this.companies       = companies;
        this.numbers         = numbers;
        this.scopeGuard      = scopeGuard;
        this.audit           = audit;
    }

    // -------------------------------------------------------------------------
    // Open
    // -------------------------------------------------------------------------

    @Override
    public BankReconciliationDto open(OpenReconciliationRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company: " + req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        CashBankAccount account = accounts.findByCompanyIdAndUid(companyId, req.cashBankAccountUid())
                .orElseThrow(() -> new NotFoundException("Cash/bank account: " + req.cashBankAccountUid()));

        if (account.getAccountType() != CashBankAccountType.BANK)
            throw new IllegalArgumentException(
                    "Bank reconciliation is only supported for BANK accounts (BR-CASH-11).");

        if (!account.isActive())
            throw new IllegalStateException("Cash/bank account is inactive (BR-CASH-08).");

        if (req.statementClosingBalance() == null)
            throw new IllegalArgumentException("statementClosingBalance is required.");

        String reconNumber = numbers.nextReconciliation(companyId);
        Long actor  = actorId();
        Long branch = branchId();

        BankReconciliation recon = new BankReconciliation(
                companyId, branch, account.getId(),
                reconNumber, req.statementDate(),
                req.statementOpeningBalance(),
                req.statementClosingBalance(), actor);
        recon = reconciliations.save(recon);

        audit.record(AuditEvent.of(AuditActions.CASH_RECONCILE_OPEN, "bank_reconciliations",
                        recon.getId(), recon.getUid())
                .detail(Map.of("reconNumber", reconNumber,
                               "accountUid", req.cashBankAccountUid(),
                               "statementDate", req.statementDate().toString())));

        return toDto(recon);
    }

    // -------------------------------------------------------------------------
    // Mark cleared / uncleared
    // -------------------------------------------------------------------------

    @Override
    public BankReconciliationDto markCleared(String reconciliationUid, MarkClearedRequest req) {
        BankReconciliation recon = Lookups.orNotFound(
                reconciliations.findByUid(reconciliationUid), "BankReconciliation", reconciliationUid);
        scopeGuard.assertCanActIn(RequestContext.get(), recon.getCompanyId());

        if (recon.getStatus() != ReconciliationStatus.DRAFT)
            throw new IllegalStateException(
                    "Reconciliation " + reconciliationUid + " is COMPLETED — cleared flags are immutable (BR-CASH-07).");

        List<CashTransaction> transactions = txns.findByUidIn(req.transactionUids());

        for (CashTransaction t : transactions) {
            if (!t.getCashBankAccountId().equals(recon.getCashBankAccountId()))
                throw new IllegalArgumentException(
                        "Transaction " + t.getUid() + " does not belong to the reconciled account.");
            if (req.cleared()) {
                t.setCleared(true);
                t.setClearedInReconciliationId(recon.getId());
            } else {
                t.setCleared(false);
                t.setClearedInReconciliationId(null);
            }
            t.setUpdatedAt(Instant.now());
            t.setUpdatedBy(actorId());
        }
        txns.saveAll(transactions);

        audit.record(AuditEvent.of(AuditActions.CASH_RECONCILE_MARK, "bank_reconciliations",
                        recon.getId(), recon.getUid())
                .detail(Map.of("cleared", req.cleared(),
                               "txnCount", transactions.size())));

        return toDto(recon);
    }

    // -------------------------------------------------------------------------
    // Complete
    // -------------------------------------------------------------------------

    @Override
    public BankReconciliationDto complete(String reconciliationUid) {
        BankReconciliation recon = Lookups.orNotFound(
                reconciliations.findByUid(reconciliationUid), "BankReconciliation", reconciliationUid);
        scopeGuard.assertCanActIn(RequestContext.get(), recon.getCompanyId());

        if (recon.getStatus() != ReconciliationStatus.DRAFT)
            throw new IllegalStateException(
                    "Reconciliation " + reconciliationUid + " is already COMPLETED (BR-CASH-07).");

        // Compute the cleared book balance for this reconciliation (D-6)
        BigDecimal clearedBalance = txns.clearedBookBalance(recon.getId());
        if (clearedBalance == null) clearedBalance = BigDecimal.ZERO;

        // BR-CASH-06: cleared book balance must equal the statement closing balance
        if (clearedBalance.compareTo(recon.getStatementClosingBalance()) != 0) {
            throw new IllegalStateException(String.format(
                    "Cannot complete reconciliation %s: cleared book balance %s ≠ statement closing balance %s (BR-CASH-06).",
                    reconciliationUid, clearedBalance.toPlainString(),
                    recon.getStatementClosingBalance().toPlainString()));
        }

        Long actor = actorId();
        recon.setClearedBookBalance(clearedBalance);
        recon.setUnreconciledAmount(recon.getStatementClosingBalance().subtract(clearedBalance));
        recon.setStatus(ReconciliationStatus.COMPLETED);
        recon.setReconciledBy(actor);
        recon.setCompletedAt(Instant.now());
        recon.setUpdatedAt(Instant.now());
        recon.setUpdatedBy(actor);
        recon = reconciliations.save(recon);

        audit.record(AuditEvent.of(AuditActions.CASH_RECONCILE_COMPLETE, "bank_reconciliations",
                        recon.getId(), recon.getUid())
                .detail(Map.of("clearedBookBalance", clearedBalance.toPlainString(),
                               "statementClosingBalance", recon.getStatementClosingBalance().toPlainString())));

        return toDto(recon);
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public BankReconciliationDto getByUid(String uid) {
        BankReconciliation recon = Lookups.orNotFound(
                reconciliations.findByUid(uid), "BankReconciliation", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), recon.getCompanyId());
        return toDto(recon);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BankReconciliationDto> listByAccount(Long companyId, Long accountId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return reconciliations.findByCashBankAccountId(accountId)
                .stream().map(BankReconciliationServiceImpl::toDto).toList();
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

    static BankReconciliationDto toDto(BankReconciliation r) {
        return new BankReconciliationDto(
                r.getId(), r.getUid(), r.getCompanyId(), r.getCashBankAccountId(),
                r.getReconciliationNumber(), r.getStatementDate(),
                r.getStatementOpeningBalance(), r.getStatementClosingBalance(),
                r.getClearedBookBalance(), r.getUnreconciledAmount(),
                r.getStatus(), r.getReconciledBy(), r.getCompletedAt());
    }
}
