package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.ChequeDto;
import com.erp.modules.cashbank.domain.dto.RegisterChequeRequest;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.entity.Cheque;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.domain.enums.ChequeStatus;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.repository.ChequeRepository;
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
 * Cheque register: ISSUED → CLEARED or CANCELLED. BANK account only (FR-CASH-10/11, D-5).
 * Cheque number unique per bank account (BR-CASH-12). The GL effect rides the settled payment.
 */
@Service
@Transactional
public class ChequeServiceImpl implements ChequeService {

    private final ChequeRepository          cheques;
    private final CashBankAccountRepository accounts;
    private final CompanyRepository         companies;
    private final ScopeGuard                scopeGuard;
    private final AuditService              audit;

    public ChequeServiceImpl(ChequeRepository cheques,
                              CashBankAccountRepository accounts,
                              CompanyRepository companies,
                              ScopeGuard scopeGuard,
                              AuditService audit) {
        this.cheques   = cheques;
        this.accounts  = accounts;
        this.companies = companies;
        this.scopeGuard = scopeGuard;
        this.audit     = audit;
    }

    @Override
    public ChequeDto register(RegisterChequeRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company: " + req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");

        CashBankAccount account = accounts.findByCompanyIdAndUid(companyId, req.cashBankAccountUid())
                .orElseThrow(() -> new NotFoundException("Cash/bank account: " + req.cashBankAccountUid()));

        if (account.getAccountType() != CashBankAccountType.BANK)
            throw new IllegalArgumentException("Cheques may only be registered against BANK accounts (FR-CASH-10).");

        if (req.valueDate().isBefore(req.issueDate()))
            throw new IllegalArgumentException("value_date must be >= issue_date (chk_cheque_dates).");

        // Uniqueness: cheque number per bank account (BR-CASH-12)
        if (cheques.findByCashBankAccountIdAndChequeNumber(account.getId(), req.chequeNumber()).isPresent()) {
            throw new ConflictException("Cheque number " + req.chequeNumber()
                    + " already exists for this bank account (BR-CASH-12).");
        }

        Long actor = actorId();
        Cheque cheque = new Cheque(
                companyId, branchId(), account.getId(),
                req.chequeNumber(), req.payee(), req.amount(), currency,
                req.issueDate(), req.valueDate(),
                req.apPaymentUid(), req.cashTransactionUid(), actor);
        cheque = cheques.save(cheque);

        audit.record(AuditEvent.of(AuditActions.CHEQUE_REGISTER, "cheques",
                        cheque.getId(), cheque.getUid())
                .detail(Map.of("chequeNumber", req.chequeNumber(), "payee", req.payee())));

        return toDto(cheque);
    }

    @Override
    public ChequeDto clear(String uid) {
        Cheque cheque = Lookups.orNotFound(cheques.findByUid(uid), "Cheque", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), cheque.getCompanyId());
        assertTransitionAllowed(cheque, ChequeStatus.CLEARED);
        cheque.setStatus(ChequeStatus.CLEARED);
        cheque.setClearedAt(Instant.now());
        cheque.setUpdatedAt(Instant.now());
        cheque.setUpdatedBy(actorId());
        cheque = cheques.save(cheque);

        audit.record(AuditEvent.of(AuditActions.CHEQUE_CLEAR, "cheques",
                        cheque.getId(), cheque.getUid())
                .detail(Map.of("uid", uid)));
        return toDto(cheque);
    }

    @Override
    public ChequeDto cancel(String uid) {
        Cheque cheque = Lookups.orNotFound(cheques.findByUid(uid), "Cheque", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), cheque.getCompanyId());
        assertTransitionAllowed(cheque, ChequeStatus.CANCELLED);
        cheque.setStatus(ChequeStatus.CANCELLED);
        cheque.setCancelledAt(Instant.now());
        cheque.setUpdatedAt(Instant.now());
        cheque.setUpdatedBy(actorId());
        cheque = cheques.save(cheque);

        audit.record(AuditEvent.of(AuditActions.CHEQUE_CANCEL, "cheques",
                        cheque.getId(), cheque.getUid())
                .detail(Map.of("uid", uid)));
        return toDto(cheque);
    }

    @Override
    @Transactional(readOnly = true)
    public ChequeDto getByUid(String uid) {
        Cheque cheque = Lookups.orNotFound(cheques.findByUid(uid), "Cheque", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), cheque.getCompanyId());
        return toDto(cheque);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChequeDto> listByCompany(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return cheques.findByCompanyId(companyId).stream()
                .map(ChequeServiceImpl::toDto).toList();
    }

    // -------------------------------------------------------------------------

    private void assertTransitionAllowed(Cheque cheque, ChequeStatus target) {
        if (cheque.getStatus() != ChequeStatus.ISSUED) {
            throw new IllegalStateException(
                    "Cheque " + cheque.getChequeNumber() + " is already in terminal state "
                            + cheque.getStatus() + " — cannot transition to " + target + " (D-5).");
        }
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private Long branchId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.branchId() : null;
    }

    static ChequeDto toDto(Cheque c) {
        return new ChequeDto(
                c.getId(), c.getUid(), c.getCompanyId(), c.getCashBankAccountId(),
                c.getChequeNumber(), c.getPayee(), c.getAmount(), c.getCurrency(),
                c.getIssueDate(), c.getValueDate(), c.getStatus(),
                c.getApPaymentUid(), c.getCashTransactionUid(),
                c.getClearedAt(), c.getCancelledAt());
    }
}
