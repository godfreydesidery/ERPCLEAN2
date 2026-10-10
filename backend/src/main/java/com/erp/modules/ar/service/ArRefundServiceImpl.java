package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArRefundDto;
import com.erp.modules.ar.domain.dto.RefundCustomerRequest;
import com.erp.modules.ar.domain.entity.ArCreditNote;
import com.erp.modules.ar.domain.entity.ArReceipt;
import com.erp.modules.ar.domain.enums.ArCreditNoteOrigin;
import com.erp.modules.ar.domain.enums.ArCreditNoteStatus;
import com.erp.modules.ar.domain.enums.ArReceiptStatus;
import com.erp.modules.ar.repository.ArCreditNoteRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
import com.erp.modules.cashbank.domain.dto.CashAccountGlResolutionDto;
import com.erp.modules.cashbank.service.CashBankAccountResolver;
import com.erp.modules.cashbank.service.CashTransactionRecorder;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ARC-11: pay a customer's unused credit back.
 *
 * <p><b>Accounting.</b> Money held on account (an over-paid or deposit receipt) and an unused credit
 * note both sit as a credit on AR control. Paying it back is DR AR control / CR the cash or bank
 * account's GL — balanced by construction — plus an OUT {@code AR_RECEIPT} cash-book row on that
 * account. {@code AR_RECEIPT} rather than {@code SALE_REFUND}: the money leaves against the
 * customer's account, not a sale; {@code SALE_REFUND} is reserved for the mirror of a voided sale's
 * GL reversal, and keeping the two apart keeps "sales voided" and "customer credit paid back"
 * separable on the cash book.
 *
 * <p><b>No refund table (no schema change).</b> The refund lives as its journal (source = the
 * receipt, or the credit note's uid), its cash row (source_ref = the document's uid) and the reduced
 * unallocated / unapplied amount. The cash rows are what later commands read to know a document
 * was partly refunded ({@link CashTransactionRecorder#refundedAmount}).
 *
 * <p>Base currency only; a foreign-currency document is refused with a pointer to a journal.
 */
@Service
@Transactional
public class ArRefundServiceImpl implements ArRefundService {

    private final ArReceiptRepository receipts;
    private final ArCreditNoteRepository creditNotes;
    private final CompanyRepository companies;
    private final CashBankAccountResolver cashAccounts;
    private final CashTransactionRecorder cashTxnRecorder;
    private final GLConfigResolver glConfig;
    private final GLPostingService glPosting;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public ArRefundServiceImpl(ArReceiptRepository receipts,
                               ArCreditNoteRepository creditNotes,
                               CompanyRepository companies,
                               CashBankAccountResolver cashAccounts,
                               CashTransactionRecorder cashTxnRecorder,
                               GLConfigResolver glConfig,
                               GLPostingService glPosting,
                               ScopeGuard scopeGuard,
                               AuditService audit) {
        this.receipts        = receipts;
        this.creditNotes     = creditNotes;
        this.companies       = companies;
        this.cashAccounts    = cashAccounts;
        this.cashTxnRecorder = cashTxnRecorder;
        this.glConfig        = glConfig;
        this.glPosting       = glPosting;
        this.scopeGuard      = scopeGuard;
        this.audit           = audit;
    }

    @Override
    public ArRefundDto refund(RefundCustomerRequest req) {
        String why = req.reason() == null ? "" : req.reason().trim();
        if (why.isEmpty()) {
            throw new IllegalArgumentException("Give a reason for the refund.");
        }
        BigDecimal amount = req.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("The refund must be more than zero.");
        }
        return RefundCustomerRequest.CREDIT_NOTE.equals(req.sourceType())
                ? refundCreditNote(req, amount, why)
                : refundReceipt(req, amount, why);
    }

    // -------------------------------------------------------------------------

    private ArRefundDto refundReceipt(RefundCustomerRequest req, BigDecimal amount, String why) {
        // Scope from the LOADED document, never a caller parameter (tenant-isolation rule).
        Long companyId = receipts.findByUid(req.sourceUid()).map(ArReceipt::getCompanyId)
                .orElseThrow(() -> new NotFoundException("Receipt not found."));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        ArReceipt receipt = receipts.findForUpdate(companyId, req.sourceUid())
                .orElseThrow(() -> new NotFoundException("Receipt not found."));
        String number = receipt.getReceiptNumber();

        if (receipt.getReversedAt() != null) {
            throw new ConflictException("Receipt " + number + " has been reversed, so there is"
                    + " nothing on it to refund.");
        }
        String currency = assertBaseCurrency(companyId, receipt.getCurrency().value(), "Receipt " + number);
        BigDecimal credit = receipt.getUnallocatedAmount() == null
                ? BigDecimal.ZERO : receipt.getUnallocatedAmount();
        assertWithinCredit(amount, credit, currency, "receipt " + number);
        LocalDate date = refundDate(req, receipt.getReceiptDate());

        Posted posted = post(companyId, receipt.getBranchId(), date, amount, currency,
                req.cashBankAccountUid(), JournalSourceType.AR_RECEIPT, receipt.getUid(),
                "Refund to customer from receipt " + number + " - " + why,
                receipt.getUid(), receipt.getBranchId());

        BigDecimal remaining = credit.subtract(amount);
        receipt.setUnallocatedAmount(remaining);
        receipt.setStatus(remaining.signum() == 0 ? ArReceiptStatus.ALLOCATED
                : remaining.compareTo(receipt.getAmount()) == 0 ? ArReceiptStatus.UNALLOCATED
                : ArReceiptStatus.PARTIAL);
        receipt.setUpdatedAt(Instant.now());
        receipt.setUpdatedBy(actorId());
        receipts.save(receipt);

        audit(companyId, "ar_receipts", receipt.getId(), receipt.getUid(), RefundCustomerRequest.RECEIPT,
                number, amount, posted.uid(), why);
        return new ArRefundDto(RefundCustomerRequest.RECEIPT, receipt.getUid(), number,
                receipt.getCustomerId(), amount, currency, date, posted.cashBankAccountUid(),
                posted.uid(), remaining);
    }

    private ArRefundDto refundCreditNote(RefundCustomerRequest req, BigDecimal amount, String why) {
        Long companyId = creditNotes.findByUid(req.sourceUid()).map(ArCreditNote::getCompanyId)
                .orElseThrow(() -> new NotFoundException("Credit note not found."));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        ArCreditNote note = creditNotes.findForUpdate(companyId, req.sourceUid())
                .orElseThrow(() -> new NotFoundException("Credit note not found."));
        String number = note.getCreditNoteNumber();

        if (note.getOrigin() == ArCreditNoteOrigin.SALE_VOID) {
            throw new ConflictException("Credit note " + number + " records a voided sale; the void"
                    + " already settled it, so it cannot be refunded here.");
        }
        String currency = assertBaseCurrency(companyId, note.getCurrency().value(), "Credit note " + number);
        BigDecimal credit = note.getUnappliedAmount() == null ? BigDecimal.ZERO : note.getUnappliedAmount();
        assertWithinCredit(amount, credit, currency, "credit note " + number);
        LocalDate date = refundDate(req, note.getNoteDate());

        // Source ref = the note's UID: the note's own entries carry its NUMBER, and re-applying a
        // note retires every live entry under that number except the raise — a refund must not be
        // swept up with them.
        Posted posted = post(companyId, note.getBranchId(), date, amount, currency,
                req.cashBankAccountUid(), JournalSourceType.AR_CREDIT_NOTE, note.getUid(),
                "Refund to customer of credit note " + number + " - " + why,
                note.getUid(), note.getBranchId());

        BigDecimal remaining = credit.subtract(amount);
        note.setUnappliedAmount(remaining);
        if (note.getBaseUnappliedAmount() != null) {
            note.setBaseUnappliedAmount(note.getBaseUnappliedAmount().subtract(amount).max(BigDecimal.ZERO));
        }
        note.setStatus(remaining.signum() == 0 ? ArCreditNoteStatus.APPLIED
                : remaining.compareTo(note.getAmount()) == 0 ? ArCreditNoteStatus.UNAPPLIED
                : ArCreditNoteStatus.PARTIAL);
        note.setUpdatedAt(Instant.now());
        note.setUpdatedBy(actorId());
        creditNotes.save(note);

        audit(companyId, "ar_credit_notes", note.getId(), note.getUid(), RefundCustomerRequest.CREDIT_NOTE,
                number, amount, posted.uid(), why);
        return new ArRefundDto(RefundCustomerRequest.CREDIT_NOTE, note.getUid(), number,
                note.getCustomerId(), amount, currency, date, posted.cashBankAccountUid(),
                posted.uid(), remaining);
    }

    // -------------------------------------------------------------------------

    /** The posted refund journal and the account the money left. */
    private record Posted(JournalEntryDto journal, String cashBankAccountUid) {
        String uid() {
            return journal.uid();
        }
    }

    /**
     * DR AR control / CR the cash-bank account's GL, then the OUT cash-book row on that account,
     * both in the caller's transaction (a GL refusal — closed period, missing setup — rolls the
     * whole refund back).
     */
    private Posted post(Long companyId, Long branchId, LocalDate date, BigDecimal amount,
                        String currency, String cashBankAccountUid, JournalSourceType sourceType,
                        String sourceRef, String memo, String cashSourceRef, Long cashBranchId) {
        CashAccountGlResolutionDto account = cashAccounts.resolve(companyId, cashBankAccountUid);
        var arAcct = glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_RECEIVABLE);
        String description = memo.length() <= 255 ? memo : memo.substring(0, 255);
        JournalEntryDto journal = glPosting.post(new JournalEntryDraft(
                companyId, branchId, date, description, sourceType, sourceRef, null, actorId(),
                List.of(new LineDraft(arAcct.getId(), amount, BigDecimal.ZERO, currency,
                                "Customer credit refunded"),
                        new LineDraft(account.glAccountId(), BigDecimal.ZERO, amount, currency,
                                "Refund paid out"))));
        cashTxnRecorder.recordCustomerRefund(companyId, cashBranchId, account.cashBankAccountId(),
                amount, currency, cashSourceRef, journal.uid(), date, memo, actorId());
        return new Posted(journal, account.cashBankAccountUid());
    }

    private String assertBaseCurrency(Long companyId, String docCurrency, String what) {
        String base = companies.findScopedById(companyId).map(c -> c.getBaseCurrency()).orElse("TZS");
        if (docCurrency != null && !docCurrency.equals(base)) {
            throw new ConflictException(what + " is in " + docCurrency + ". Refunds in a foreign"
                    + " currency are not supported here yet; ask your accountant to post a journal.");
        }
        return base;
    }

    private static void assertWithinCredit(BigDecimal amount, BigDecimal credit, String currency,
                                           String what) {
        if (credit.signum() <= 0) {
            throw new ConflictException("There is no unused credit left on " + what + " to refund.");
        }
        if (amount.compareTo(credit) > 0) {
            throw new ConflictException("You can refund at most " + currency + " "
                    + credit.stripTrailingZeros().toPlainString() + " from " + what
                    + " - that is the credit still unused on it.");
        }
    }

    private static LocalDate refundDate(RefundCustomerRequest req, LocalDate documentDate) {
        LocalDate date = req.refundDate() != null ? req.refundDate() : LocalDate.now(ZoneOffset.UTC);
        if (documentDate != null && date.isBefore(documentDate)) {
            throw new IllegalArgumentException("The refund cannot be dated before the document it refunds.");
        }
        return date;
    }

    private void audit(Long companyId, String table, Long id, String uid, String sourceType,
                       String number, BigDecimal amount, String journalUid, String why) {
        audit.record(AuditEvent.of(AuditActions.AR_REFUND, table, id, uid)
                .detail(Map.of(
                        "sourceType", sourceType,
                        "documentNumber", number,
                        "amount", amount.toPlainString(),
                        "journalEntryUid", journalUid,
                        "reason", why)));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
