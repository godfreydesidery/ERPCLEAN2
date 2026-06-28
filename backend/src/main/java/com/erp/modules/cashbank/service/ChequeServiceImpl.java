package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.ChequeBouncedPayload;
import com.erp.modules.cashbank.domain.dto.ChequeDto;
import com.erp.modules.cashbank.domain.dto.RegisterChequeRequest;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.entity.Cheque;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.domain.enums.ChequeDirection;
import com.erp.modules.cashbank.domain.enums.ChequeStatus;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.repository.ChequeRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
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
    private final OutboxPublisher           outbox;
    private final ScopeGuard                scopeGuard;
    private final AuditService              audit;

    public ChequeServiceImpl(ChequeRepository cheques,
                              CashBankAccountRepository accounts,
                              CompanyRepository companies,
                              OutboxPublisher outbox,
                              ScopeGuard scopeGuard,
                              AuditService audit) {
        this.cheques   = cheques;
        this.accounts  = accounts;
        this.companies = companies;
        this.outbox    = outbox;
        this.scopeGuard = scopeGuard;
        this.audit     = audit;
    }

    @Override
    public ChequeDto register(RegisterChequeRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");

        CashBankAccount account = accounts.findByCompanyIdAndUid(companyId, req.cashBankAccountUid())
                .orElseThrow(() -> new NotFoundException("Cash/bank account not found."));

        // FR-CASH-10: cheques are only valid for bank accounts, not petty-cash accounts
        if (account.getAccountType() != CashBankAccountType.BANK)
            throw new IllegalArgumentException("Cheques can only be registered against a bank account, not a petty-cash account.");

        if (req.valueDate().isBefore(req.issueDate()))
            throw new IllegalArgumentException("value_date must be >= issue_date (chk_cheque_dates).");

        // BR-CASH-12: cheque number must be unique per bank account
        if (cheques.findByCashBankAccountIdAndChequeNumber(account.getId(), req.chequeNumber()).isPresent()) {
            throw new ConflictException("Cheque number " + req.chequeNumber()
                    + " has already been registered for this bank account.");
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
        assertCancelAllowed(cheque);
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

    /**
     * INBOUND only: ISSUED → DEPOSITED.
     * Records the bank-deposit timestamp. No GL posting (ADR-0016 D-5; GL rides the AR receipt).
     */
    @Override
    public ChequeDto deposit(String uid) {
        Cheque cheque = Lookups.orNotFound(cheques.findByUid(uid), "Cheque", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), cheque.getCompanyId());

        if (cheque.getDirection() != ChequeDirection.INBOUND) {
            throw new IllegalStateException(
                    "deposit() is only valid for INBOUND cheques; this cheque is "
                    + cheque.getDirection() + ".");
        }
        if (cheque.getStatus() != ChequeStatus.ISSUED
                && cheque.getStatus() != ChequeStatus.BOUNCED) {
            throw new IllegalStateException(
                    "Cannot deposit cheque in status " + cheque.getStatus()
                    + " — must be ISSUED or BOUNCED.");
        }

        // Track re-presentations after a bounce
        if (cheque.getStatus() == ChequeStatus.BOUNCED) {
            cheque.setRepresentCount((short) (cheque.getRepresentCount() + 1));
        }

        cheque.setStatus(ChequeStatus.DEPOSITED);
        cheque.setDepositedAt(Instant.now());
        cheque.setUpdatedAt(Instant.now());
        cheque.setUpdatedBy(actorId());
        cheque = cheques.save(cheque);

        audit.record(AuditEvent.of(AuditActions.CHEQUE_CLEAR, "cheques",
                        cheque.getId(), cheque.getUid())
                .detail(Map.of("uid", uid, "action", "deposit")));
        return toDto(cheque);
    }

    /**
     * INBOUND only: DEPOSITED → BOUNCED (ADR-0041 D3 — closes the deferred D-9 follow-up).
     *
     * <p>Records bounce timestamp/reason and publishes {@code CHEQUE.BOUNCED} on the transactional
     * outbox in this TX. The AR reversal handler ({@code ChequeBounceReversalHandler}) consumes it
     * and posts an APPEND-ONLY reversing JournalEntry of the owning AR receipt's cash leg (never
     * mutating a posted entry), stamps {@code reversed_at}, and restores the invoice outstanding.
     *
     * <p>The event row commits atomically with the BOUNCED state change — if this TX rolls back, no
     * event is dispatched. (An OUTBOUND cheque tied to an AP payment is reversed symmetrically by
     * the AP handler, but the OUTBOUND bounce transition is not modelled here — D-9 keeps bounce as
     * an INBOUND-only register transition; OUTBOUND reversal arrives via the same event when an
     * OUTBOUND bounce path is added.)
     */
    @Override
    public ChequeDto bounce(String uid, String bounceReason) {
        Cheque cheque = Lookups.orNotFound(cheques.findByUid(uid), "Cheque", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), cheque.getCompanyId());

        if (cheque.getDirection() != ChequeDirection.INBOUND) {
            throw new IllegalStateException(
                    "bounce() is only valid for INBOUND cheques; this cheque is "
                    + cheque.getDirection() + ".");
        }
        if (cheque.getStatus() != ChequeStatus.DEPOSITED) {
            throw new IllegalStateException(
                    "Cannot bounce cheque in status " + cheque.getStatus()
                    + " — must be DEPOSITED.");
        }

        cheque.setStatus(ChequeStatus.BOUNCED);
        cheque.setBouncedAt(Instant.now());
        cheque.setBounceReason(bounceReason);
        cheque.setUpdatedAt(Instant.now());
        cheque.setUpdatedBy(actorId());
        cheque = cheques.save(cheque);

        audit.record(AuditEvent.of(AuditActions.CHEQUE_CANCEL, "cheques",
                        cheque.getId(), cheque.getUid())
                .detail(Map.of("uid", uid, "action", "bounce", "reason",
                        bounceReason != null ? bounceReason : "")));

        // ADR-0041 D3: publish the bounce so the owning module reverses the cash leg (append-only).
        outbox.publish(DomainEventType.CHEQUE_BOUNCED, DomainEventType.AGG_CHEQUE,
                cheque.getId(), cheque.getUid(), cheque.getCompanyId(), cheque.getBranchId(),
                new ChequeBouncedPayload(
                        cheque.getUid(),
                        cheque.getDirection().name(),
                        cheque.getArReceiptUid(),
                        cheque.getApPaymentUid(),
                        bounceReason));

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

    /** OUTBOUND clear: must be ISSUED. */
    // D-5: only an ISSUED outbound cheque may be marked cleared
    private void assertTransitionAllowed(Cheque cheque, ChequeStatus target) {
        if (cheque.getStatus() != ChequeStatus.ISSUED) {
            throw new IllegalStateException(
                    "Cheque " + cheque.getChequeNumber() + " is already in status "
                            + cheque.getStatus() + " and cannot be marked as " + target + ".");
        }
    }

    /** Cancel: ISSUED or DEPOSITED may be cancelled; CLEARED/BOUNCED are terminal. */
    // D-5/D-9: cleared and bounced cheques are in a terminal state and cannot be cancelled
    private void assertCancelAllowed(Cheque cheque) {
        if (cheque.getStatus() == ChequeStatus.CLEARED
                || cheque.getStatus() == ChequeStatus.BOUNCED) {
            throw new IllegalStateException(
                    "Cheque " + cheque.getChequeNumber() + " has already been "
                            + cheque.getStatus().toString().toLowerCase()
                            + " and cannot be cancelled.");
        }
        if (cheque.getStatus() == ChequeStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Cheque " + cheque.getChequeNumber() + " has already been cancelled.");
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
                c.getChequeNumber(), c.getPayee(), c.getAmount(), CurrencyCode.value(c.getCurrency()),
                c.getIssueDate(), c.getValueDate(), c.getStatus(),
                c.getApPaymentUid(), c.getCashTransactionUid(),
                c.getClearedAt(), c.getCancelledAt(),
                // D-9: bidirectional fields
                c.getDirection(), c.getArReceiptUid(),
                c.getDepositedAt(), c.getBouncedAt(), c.getBounceReason(),
                c.getRepresentCount());
    }
}
