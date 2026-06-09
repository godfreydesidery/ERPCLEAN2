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
}
