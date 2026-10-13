package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.entity.CashTransaction;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal component that appends a cash_transactions row from within an AR or AP service TX
 * (ADR-0016 D-8). Called AFTER the GL post; the journal_entry_ref is the receipt's/payment's
 * own GL entry uid. The recorder runs in the SAME AR/AP transaction — same atomicity guarantee.
 */
@Component
public class CashTransactionRecorder {

    private final CashTransactionRepository txns;
    private final CashBankNumberGenerator   numbers;
    private final AuditService              audit;

    public CashTransactionRecorder(CashTransactionRepository txns,
                                    CashBankNumberGenerator numbers,
                                    AuditService audit) {
        this.txns    = txns;
        this.numbers = numbers;
        this.audit   = audit;
    }

    /**
     * Records an AR receipt or AP payment settlement as a cash_transactions row.
     * Must be called inside an active write transaction (MANDATORY propagation).
     *
     * @param companyId          the company
     * @param branchId           the branch (may be null)
     * @param cashBankAccountId  the resolved cash/bank account id
     * @param txnType            AR_RECEIPT or AP_PAYMENT
     * @param direction          IN for receipt, OUT for payment
     * @param amount             the settled amount (positive)
     * @param currency           base currency
     * @param sourceRef          uid of the AR receipt or AP payment row
     * @param journalEntryRef    uid of the GL journal entry posted by AR/AP
     * @param txnDate            the settlement date
     * @param actorId            the operator (for audit)
     * @return the saved CashTransaction id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long recordSettlement(Long companyId, Long branchId, Long cashBankAccountId,
                                  CashTxnType txnType, CashTxnDirection direction,
                                  BigDecimal amount, String currency,
                                  String sourceRef, String journalEntryRef,
                                  LocalDate txnDate, Long actorId) {
        String txnNumber = numbers.nextTransaction(companyId);

        CashTransaction txn = new CashTransaction(
                companyId, branchId, cashBankAccountId,
                txnNumber, txnDate,
                direction, amount, currency,
                txnType, sourceRef, null, null, actorId);
        txn.setJournalEntryRef(journalEntryRef);
        txn = txns.save(txn);

        audit.record(AuditEvent.of(AuditActions.CASH_SETTLEMENT_RECORD, "cash_transactions",
                        txn.getId(), txn.getUid())
                .detail(Map.of(
                        "txnType",     txnType.name(),
                        "sourceRef",   sourceRef != null ? sourceRef : "",
                        "amount",      amount.toPlainString(),
                        "accountId",   String.valueOf(cashBankAccountId))));

        return txn.getId();
    }

    /**
     * The amount of the live settlement row a document wrote (ARC-04): the {@code txnType} /
     * {@code direction} row whose {@code source_ref} is the document uid and which is not itself a
     * reversal. Empty for a document recorded before the cash book existed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BigDecimal> settledAmount(Long companyId, String sourceRef,
                                              CashTxnType txnType, CashTxnDirection direction) {
        return findSettlement(companyId, sourceRef, txnType, direction).map(CashTransaction::getAmount);
    }

    private Optional<CashTransaction> findSettlement(Long companyId, String sourceRef,
                                                     CashTxnType txnType, CashTxnDirection direction) {
        return txns.findByCompanyIdAndSourceRef(companyId, sourceRef).stream()
                .filter(t -> t.getTxnType() == txnType && t.getDirection() == direction
                        && t.getReversalOfTransactionId() == null)
                .findFirst();
    }

    /**
     * Appends the mirror of a settlement row (ARC-04): same account, amount and currency, the
     * opposite direction, linked back through {@code reversal_of_transaction_id}. The original row
     * is never touched (the cash book is append-only like the GL).
     *
     * @return the saved reversal row id, or empty when the document has no live settlement row
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> recordSettlementReversal(Long companyId, String sourceRef,
                                                   CashTxnType txnType, CashTxnDirection direction,
                                                   String journalEntryRef, LocalDate txnDate,
                                                   String memo, Long actorId) {
        CashTransaction original = findSettlement(companyId, sourceRef, txnType, direction)
                .orElse(null);
        if (original == null) {
            return Optional.empty();
        }
        CashTxnDirection opposite = original.getDirection() == CashTxnDirection.IN
                ? CashTxnDirection.OUT : CashTxnDirection.IN;
        String txnNumber = numbers.nextTransaction(original.getCompanyId());
        CashTransaction txn = new CashTransaction(
                original.getCompanyId(), original.getBranchId(), original.getCashBankAccountId(),
                txnNumber, txnDate,
                opposite, original.getAmount(), original.getCurrency().value(),
                original.getTxnType(), original.getSourceRef(), original.getCounterGlAccountId(),
                truncate(memo, 255), actorId);
        txn.setJournalEntryRef(journalEntryRef);
        txn.setReversalOfTransactionId(original.getId());
        txn = txns.save(txn);

        audit.record(AuditEvent.of(AuditActions.CASH_SETTLEMENT_RECORD, "cash_transactions",
                        txn.getId(), txn.getUid())
                .detail(Map.of(
                        "txnType",       original.getTxnType().name(),
                        "sourceRef",     original.getSourceRef() != null ? original.getSourceRef() : "",
                        "amount",        original.getAmount().toPlainString(),
                        "accountId",     String.valueOf(original.getCashBankAccountId()),
                        "reversalOfTxn", String.valueOf(original.getId()))));
        return Optional.of(txn.getId());
    }

    /**
     * A cash/bank account paying money into (or receiving it back from) a petty-cash fund
     * (ARC-10). Written as a DIRECT_ENTRY whose counter account is the petty-cash GL account, so
     * the cash book of the source account moves with the GL it was posted to.
     *
     * @return the saved row id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long recordPettyCashFunding(Long companyId, Long branchId, Long cashBankAccountId,
                                       CashTxnDirection direction, BigDecimal amount,
                                       String currency, Long pettyCashGlAccountId,
                                       String sourceRef, String journalEntryRef,
                                       LocalDate txnDate, String memo, Long actorId) {
        String txnNumber = numbers.nextTransaction(companyId);
        CashTransaction txn = new CashTransaction(
                companyId, branchId, cashBankAccountId,
                txnNumber, txnDate,
                direction, amount, currency,
                CashTxnType.DIRECT_ENTRY, sourceRef, pettyCashGlAccountId,
                truncate(memo, 255), actorId);
        txn.setJournalEntryRef(journalEntryRef);
        txn = txns.save(txn);

        audit.record(AuditEvent.of(AuditActions.CASH_SETTLEMENT_RECORD, "cash_transactions",
                        txn.getId(), txn.getUid())
                .detail(Map.of(
                        "txnType",   CashTxnType.DIRECT_ENTRY.name(),
                        "sourceRef", sourceRef != null ? sourceRef : "",
                        "amount",    amount.toPlainString(),
                        "accountId", String.valueOf(cashBankAccountId))));
        return txn.getId();
    }

    /**
     * Money paid back to a customer out of a cash/bank account (ARC-11): an OUT {@code AR_RECEIPT}
     * row against the receipt or credit note it refunds ({@code source_ref} = that document's uid),
     * mirroring the refund journal (DR AR control / CR the account). Not a reversal: the original
     * receipt row stays live, and {@link #refundedAmount} sums these rows.
     *
     * @return the saved row id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long recordCustomerRefund(Long companyId, Long branchId, Long cashBankAccountId,
                                     BigDecimal amount, String currency, String sourceRef,
                                     String journalEntryRef, LocalDate txnDate,
                                     String memo, Long actorId) {
        String txnNumber = numbers.nextTransaction(companyId);
        CashTransaction txn = new CashTransaction(
                companyId, branchId, cashBankAccountId,
                txnNumber, txnDate,
                CashTxnDirection.OUT, amount, currency,
                CashTxnType.AR_RECEIPT, sourceRef, null, truncate(memo, 255), actorId);
        txn.setJournalEntryRef(journalEntryRef);
        txn = txns.save(txn);

        audit.record(AuditEvent.of(AuditActions.CASH_SETTLEMENT_RECORD, "cash_transactions",
                        txn.getId(), txn.getUid())
                .detail(Map.of(
                        "txnType",   "AR_REFUND",
                        "sourceRef", sourceRef,
                        "amount",    amount.toPlainString(),
                        "accountId", String.valueOf(cashBankAccountId))));
        return txn.getId();
    }

    /**
     * What has been refunded against a receipt or credit note so far (ARC-11): the OUT
     * {@code AR_RECEIPT} rows on its uid that are not themselves reversals. Zero when none.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal refundedAmount(Long companyId, String sourceRef) {
        return txns.findByCompanyIdAndSourceRef(companyId, sourceRef).stream()
                .filter(t -> t.getTxnType() == CashTxnType.AR_RECEIPT
                        && t.getDirection() == CashTxnDirection.OUT
                        && t.getReversalOfTransactionId() == null)
                .map(CashTransaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
