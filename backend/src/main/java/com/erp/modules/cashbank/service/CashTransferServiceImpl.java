package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CashTransferDto;
import com.erp.modules.cashbank.domain.dto.RecordTransferRequest;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.entity.CashTransaction;
import com.erp.modules.cashbank.domain.entity.CashTransfer;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.cashbank.repository.CashTransferRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyCode;
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
 * Inter-account transfer: 2 cash_transactions + 1 balanced GL entry (ADR-0016 D-4, FR-CASH-08).
 * DR destination GL account / CR source GL account — CASH_TRANSFER source type.
 * GL failure rolls back the whole command (atomicity = reconciliation by construction, D-7).
 */
@Service
@Transactional
public class CashTransferServiceImpl implements CashTransferService {

    private final CashBankAccountRepository  accounts;
    private final CashTransactionRepository  txns;
    private final CashTransferRepository     transfers;
    private final CompanyRepository          companies;
    private final CashBankNumberGenerator    numbers;
    private final GLPostingService           glPosting;
    private final ScopeGuard                 scopeGuard;
    private final AuditService               audit;

    public CashTransferServiceImpl(CashBankAccountRepository accounts,
                                    CashTransactionRepository txns,
                                    CashTransferRepository transfers,
                                    CompanyRepository companies,
                                    CashBankNumberGenerator numbers,
                                    GLPostingService glPosting,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.accounts   = accounts;
        this.txns       = txns;
        this.transfers  = transfers;
        this.companies  = companies;
        this.numbers    = numbers;
        this.glPosting  = glPosting;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public CashTransferDto recordTransfer(RecordTransferRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");

        CashBankAccount source = accounts.findByCompanyIdAndUid(companyId, req.sourceAccountUid())
                .orElseThrow(() -> new NotFoundException("Source account not found."));
        CashBankAccount dest   = accounts.findByCompanyIdAndUid(companyId, req.destinationAccountUid())
                .orElseThrow(() -> new NotFoundException("Destination account not found."));

        if (!source.isActive()) throw new IllegalStateException("Source account is inactive (BR-CASH-08).");
        if (!dest.isActive())   throw new IllegalStateException("Destination account is inactive (BR-CASH-08).");
        if (source.getId().equals(dest.getId()))
            throw new IllegalArgumentException("Source and destination must differ (BR-CASH-04).");

        Long branchId = branchId();
        Long actor    = actorId();

        // 1. Insert OUT transaction on source
        String outNumber = numbers.nextTransaction(companyId);
        CashTransaction outTxn = new CashTransaction(
                companyId, branchId, source.getId(), outNumber, req.transferDate(),
                CashTxnDirection.OUT, req.amount(), currency,
                CashTxnType.TRANSFER_OUT, null, null, req.reference(), actor);
        outTxn = txns.save(outTxn);

        // 2. Insert IN transaction on destination
        String inNumber = numbers.nextTransaction(companyId);
        CashTransaction inTxn = new CashTransaction(
                companyId, branchId, dest.getId(), inNumber, req.transferDate(),
                CashTxnDirection.IN, req.amount(), currency,
                CashTxnType.TRANSFER_IN, null, null, req.reference(), actor);
        inTxn = txns.save(inTxn);

        // 3. Insert the transfer header (needs out/in txn ids)
        String transferNumber = numbers.nextTransfer(companyId);
        CashTransfer transfer = new CashTransfer(
                companyId, branchId, transferNumber,
                source.getId(), dest.getId(),
                req.transferDate(), req.amount(), currency,
                req.reference(), outTxn.getId(), inTxn.getId(), actor);
        transfer = transfers.save(transfer);

        // 4. Post balanced GL entry: DR dest / CR source (D-4, D-7)
        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, branchId, req.transferDate(),
                "Cash Transfer " + transferNumber,
                JournalSourceType.CASH_TRANSFER,
                transfer.getUid(), null, actor,
                List.of(
                    new LineDraft(dest.getGlAccountId(), req.amount(), BigDecimal.ZERO,
                            currency, "Transfer in — " + transferNumber),
                    new LineDraft(source.getGlAccountId(), BigDecimal.ZERO, req.amount(),
                            currency, "Transfer out — " + transferNumber)
                ));
        JournalEntryDto posted = glPosting.post(draft);

        // 5. Back-fill journal_entry_ref on both transactions + header
        outTxn.setJournalEntryRef(posted.uid());
        outTxn.setUpdatedAt(Instant.now());
        outTxn.setUpdatedBy(actor);
        txns.save(outTxn);

        inTxn.setJournalEntryRef(posted.uid());
        inTxn.setUpdatedAt(Instant.now());
        inTxn.setUpdatedBy(actor);
        txns.save(inTxn);

        transfer.setJournalEntryRef(posted.uid());
        transfer.setUpdatedAt(Instant.now());
        transfer.setUpdatedBy(actor);

        // Also back-fill source_ref on transactions → transfer uid
        outTxn.setJournalEntryRef(posted.uid());
        inTxn.setJournalEntryRef(posted.uid());

        transfer = transfers.save(transfer);

        audit.record(AuditEvent.of(AuditActions.CASH_TRANSFER_RECORD, "cash_transfers",
                        transfer.getId(), transfer.getUid())
                .detail(Map.of(
                        "transferNumber", transferNumber,
                        "amount",         req.amount().toPlainString(),
                        "glEntryUid",     posted.uid())));

        return toDto(transfer, source.getUid(), dest.getUid());
    }

    @Override
    @Transactional(readOnly = true)
    public CashTransferDto getByUid(String uid) {
        CashTransfer transfer = Lookups.orNotFound(transfers.findByUid(uid), "CashTransfer", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), transfer.getCompanyId());
        String srcUid  = accounts.findById(transfer.getSourceAccountId()).map(a -> a.getUid()).orElse(null);
        String destUid = accounts.findById(transfer.getDestinationAccountId()).map(a -> a.getUid()).orElse(null);
        return toDto(transfer, srcUid, destUid);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CashTransferDto> listByCompany(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return transfers.findByCompanyId(companyId).stream()
                .map(t -> {
                    String srcUid  = accounts.findById(t.getSourceAccountId()).map(a -> a.getUid()).orElse(null);
                    String destUid = accounts.findById(t.getDestinationAccountId()).map(a -> a.getUid()).orElse(null);
                    return toDto(t, srcUid, destUid);
                }).toList();
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

    static CashTransferDto toDto(CashTransfer t, String srcUid, String destUid) {
        return new CashTransferDto(
                t.getId(), t.getUid(), t.getCompanyId(), t.getTransferNumber(),
                t.getSourceAccountId(), srcUid,
                t.getDestinationAccountId(), destUid,
                t.getTransferDate(), t.getAmount(), CurrencyCode.value(t.getCurrency()),
                t.getReference(), t.getOutTxnId(), t.getInTxnId(), t.getJournalEntryRef());
    }
}
