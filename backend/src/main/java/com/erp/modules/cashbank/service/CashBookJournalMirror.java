package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.entity.CashTransaction;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.dto.JournalLineDto;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.JournalService;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the cash-book side of journals other modules have already posted (gap review wave 3,
 * ARC-08): sale tenders, sale voids, POS till payouts / expenses and POS session over/short.
 *
 * <p><b>A mirror, never a second posting.</b> Each method reads the posted journal back and writes
 * one cash_transactions row per journal line whose GL account is linked to a cash/bank account
 * ({@code uq_cash_bank_account_gl} makes that link one-to-one). A debit is money IN, a credit money
 * OUT, the amount is the line's base-currency amount and the row carries the journal's posting
 * date and uid. So whatever the posting decided — the tender split across accounts, change, the
 * cash-sale residual, an FX rounding plug — the cash book shows exactly that, and the Cash-vs-GL
 * reconciliation of a till stays where it was. A journal line on an account no cash/bank account
 * is linked to (AR, revenue, VAT, expense) writes nothing.
 *
 * <p><b>Idempotent per source.</b> A source document ({@code source_ref}) that already has rows of
 * the requested type is skipped, so an outbox redelivery never doubles the cash book.
 *
 * <p><b>Isolated.</b> Every entry point runs in its own transaction: a failure here is logged by
 * the calling handler and never rolls back the stock issue or the AR open item that share the
 * outbox dispatch. A source whose journal does not exist (the GL posting failed — missing setup or
 * a closed period) writes nothing: the cash book never shows money the ledger does not.
 */
@Component
public class CashBookJournalMirror {

    private static final Logger log = LoggerFactory.getLogger(CashBookJournalMirror.class);

    private final CashTransactionRepository txns;
    private final CashBankAccountRepository accounts;
    private final CashBankNumberGenerator   numbers;
    private final JournalService            journals;
    private final AuditService              audit;

    public CashBookJournalMirror(CashTransactionRepository txns,
                                 CashBankAccountRepository accounts,
                                 CashBankNumberGenerator numbers,
                                 JournalService journals,
                                 AuditService audit) {
        this.txns     = txns;
        this.accounts = accounts;
        this.numbers  = numbers;
        this.journals = journals;
        this.audit    = audit;
    }

    /**
     * IN {@code SALE_TENDER} rows for a finalised sale, mirroring its {@code SALES} journal.
     *
     * @return rows written; 0 when already mirrored or no line touches a cash/bank account; -1 when
     *         the sale has no journal
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int mirrorSale(Long companyId, Long branchId, String invoiceUid, String invoiceNumber) {
        if (hasRows(companyId, invoiceUid, CashTxnType.SALE_TENDER)) {
            return 0;
        }
        Optional<JournalEntryDto> journal =
                journals.findPostedBySource(companyId, JournalSourceType.SALES, invoiceUid);
        if (journal.isEmpty()) {
            log.warn("Cash book: sale {} has no GL entry in company {} — no cash row written",
                    invoiceUid, companyId);
            return -1;
        }
        return mirror(companyId, branchId, journal.get(), CashTxnType.SALE_TENDER, invoiceUid,
                "Sale " + label(invoiceNumber, invoiceUid), null);
    }

    /**
     * {@code SALE_REFUND} rows for a voided sale, mirroring its {@code SALES_REVERSAL} journal
     * (OUT where the sale took money in). Each row is linked to the sale's tender row on the same
     * account when there is one. A sale finalised before the cash book carried sales has no tender
     * row, but its void is still mirrored: the GL moves, so the cash book moves with it and the
     * till's Cash-vs-GL difference stays what it was.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int mirrorSaleVoid(Long companyId, Long branchId, String invoiceUid,
                              String invoiceNumber) {
        if (hasRows(companyId, invoiceUid, CashTxnType.SALE_REFUND)) {
            return 0;
        }
        Optional<JournalEntryDto> journal = journals.findPostedBySource(
                companyId, JournalSourceType.SALES_REVERSAL, invoiceUid);
        if (journal.isEmpty()) {
            log.warn("Cash book: voided sale {} has no GL reversal in company {} — no cash row "
                    + "written", invoiceUid, companyId);
            return -1;
        }
        return mirror(companyId, branchId, journal.get(), CashTxnType.SALE_REFUND, invoiceUid,
                "Sale voided " + label(invoiceNumber, invoiceUid), CashTxnType.SALE_TENDER);
    }

    /**
     * {@code POS_PAYOUT} (OUT) or {@code POS_VARIANCE} (IN over / OUT short) rows mirroring the
     * till journal identified by {@code journalUid}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int mirrorPosCash(Long companyId, Long branchId, CashTxnType type, String sourceUid,
                             String journalUid, String memo) {
        if (type != CashTxnType.POS_PAYOUT && type != CashTxnType.POS_VARIANCE) {
            throw new IllegalArgumentException("Not a POS cash type: " + type);
        }
        if (hasRows(companyId, sourceUid, type)) {
            return 0;
        }
        Optional<JournalEntryDto> journal = journals.findPostedByUid(companyId, journalUid);
        if (journal.isEmpty()) {
            log.warn("Cash book: POS journal {} not found in company {} — no cash row written",
                    journalUid, companyId);
            return -1;
        }
        return mirror(companyId, branchId, journal.get(), type, sourceUid, memo, null);
    }

    // -------------------------------------------------------------------------

    private boolean hasRows(Long companyId, String sourceRef, CashTxnType type) {
        return txns.findByCompanyIdAndSourceRef(companyId, sourceRef).stream()
                .anyMatch(t -> t.getTxnType() == type);
    }

    private int mirror(Long companyId, Long branchId, JournalEntryDto journal, CashTxnType type,
                       String sourceRef, String memo, CashTxnType linkToType) {
        // Net per GL account (a journal may carry more than one line on the same account).
        Map<Long, BigDecimal> net = new LinkedHashMap<>();
        Map<Long, String> currency = new LinkedHashMap<>();
        for (JournalLineDto line : journal.lines() == null ? List.<JournalLineDto>of()
                : journal.lines()) {
            BigDecimal dr = line.debitAmount() != null ? line.debitAmount() : BigDecimal.ZERO;
            BigDecimal cr = line.creditAmount() != null ? line.creditAmount() : BigDecimal.ZERO;
            net.merge(line.accountId(), dr.subtract(cr), BigDecimal::add);
            currency.putIfAbsent(line.accountId(), line.currency());
        }

        List<CashTransaction> originals = linkToType == null ? List.of()
                : txns.findByCompanyIdAndSourceRef(companyId, sourceRef).stream()
                        .filter(t -> t.getTxnType() == linkToType
                                && t.getReversalOfTransactionId() == null)
                        .toList();

        int written = 0;
        for (Map.Entry<Long, BigDecimal> e : net.entrySet()) {
            BigDecimal amount = e.getValue();
            if (amount.signum() == 0) {
                continue;
            }
            CashBankAccount account = accounts.findByCompanyIdAndGlAccountId(companyId, e.getKey())
                    .orElse(null);
            if (account == null) {
                continue; // AR, revenue, VAT, expense … — not a cash/bank account
            }
            CashTxnDirection direction = amount.signum() > 0
                    ? CashTxnDirection.IN : CashTxnDirection.OUT;
            CashTransaction txn = new CashTransaction(
                    companyId, branchId, account.getId(),
                    numbers.nextTransaction(companyId), journal.postingDate(),
                    direction, amount.abs(), currency.get(e.getKey()),
                    type, sourceRef, null, truncate(memo), null);
            txn.setJournalEntryRef(journal.uid());
            originals.stream()
                    .filter(o -> o.getCashBankAccountId().equals(account.getId()))
                    .findFirst()
                    .ifPresent(o -> txn.setReversalOfTransactionId(o.getId()));
            CashTransaction saved = txns.save(txn);
            audit.record(AuditEvent.of(AuditActions.CASH_SETTLEMENT_RECORD, "cash_transactions",
                            saved.getId(), saved.getUid())
                    .detail(Map.of(
                            "txnType",   type.name(),
                            "sourceRef", sourceRef,
                            "amount",    saved.getAmount().toPlainString(),
                            "accountId", String.valueOf(account.getId()),
                            "journal",   journal.uid())));
            written++;
        }
        return written;
    }

    private static String label(String number, String uid) {
        return number != null && !number.isBlank() ? number : uid;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
