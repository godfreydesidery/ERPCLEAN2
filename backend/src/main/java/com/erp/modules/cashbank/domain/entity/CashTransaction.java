package com.erp.modules.cashbank.domain.entity;

import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * A movement on a cash/bank account — the cash-book ledger row (ADR-0016 D-2b/D-3).
 * Append-only; corrections are reversing transactions. Book balance = SUM(IN amounts) - SUM(OUT amounts).
 */
@Getter
@Entity
@Table(name = "cash_transactions")
public class CashTransaction extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "cash_bank_account_id", nullable = false, updatable = false)
    private Long cashBankAccountId;

    @Column(name = "txn_number", nullable = false, length = 30, updatable = false)
    private String txnNumber;

    @Column(name = "txn_date", nullable = false, updatable = false)
    private LocalDate txnDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 3, updatable = false)
    private CashTxnDirection direction;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "txn_type", nullable = false, length = 20, updatable = false)
    private CashTxnType txnType;

    /** Scalar ref to the originating document uid (no FK — cross-module). */
    @Column(name = "source_ref", length = 26)
    private String sourceRef;

    /** For DIRECT_ENTRY only: the counter GL account id (FK → chart_of_accounts). */
    @Column(name = "counter_gl_account_id")
    private Long counterGlAccountId;

    /** Scalar ref to the GL journal_entries.uid of the posting for this movement. */
    @Column(name = "journal_entry_ref", length = 26)
    @Setter
    private String journalEntryRef;

    @Column(name = "cleared", nullable = false)
    @Setter
    private boolean cleared = false;

    /** FK → bank_reconciliations(id); set when cleared. */
    @Column(name = "cleared_in_reconciliation_id")
    @Setter
    private Long clearedInReconciliationId;

    @Column(name = "memo", length = 255)
    private String memo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    protected CashTransaction() {
        // JPA
    }

    public CashTransaction(Long companyId, Long branchId, Long cashBankAccountId,
                            String txnNumber, LocalDate txnDate, CashTxnDirection direction,
                            BigDecimal amount, String currency, CashTxnType txnType,
                            String sourceRef, Long counterGlAccountId,
                            String memo, Long createdBy) {
        this.companyId           = companyId;
        this.branchId            = branchId;
        this.cashBankAccountId   = cashBankAccountId;
        this.txnNumber           = txnNumber;
        this.txnDate             = txnDate;
        this.direction           = direction;
        this.amount              = amount;
        this.currency            = CurrencyCode.of(currency);
        this.txnType             = txnType;
        this.sourceRef           = sourceRef;
        this.counterGlAccountId  = counterGlAccountId;
        this.memo                = memo;
        this.createdBy           = createdBy;
    }
}
