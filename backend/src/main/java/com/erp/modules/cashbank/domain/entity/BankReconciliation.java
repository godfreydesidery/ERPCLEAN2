package com.erp.modules.cashbank.domain.entity;

import com.erp.modules.cashbank.domain.enums.ReconciliationStatus;
import com.erp.platform.common.domain.UidEntity;
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
 * Manual bank reconciliation — DRAFT → COMPLETED only when book balance of cleared transactions
 * equals the operator-entered statement closing balance (ADR-0016 D-2e/D-6, BR-CASH-06/07).
 */
@Getter
@Entity
@Table(name = "bank_reconciliations")
public class BankReconciliation extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "cash_bank_account_id", nullable = false, updatable = false)
    private Long cashBankAccountId;

    @Column(name = "reconciliation_number", nullable = false, length = 30, updatable = false)
    private String reconciliationNumber;

    @Column(name = "statement_date", nullable = false)
    @Setter
    private LocalDate statementDate;

    /**
     * Opening balance from the bank statement (continuity check / carry-forward).
     * Should equal the previous reconciliation's statementClosingBalance. Nullable —
     * not required for the first reconciliation on an account.
     */
    @Column(name = "statement_opening_balance", precision = 19, scale = 4)
    @Setter
    private BigDecimal statementOpeningBalance;

    @Column(name = "statement_closing_balance", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal statementClosingBalance;

    /** Snapshot of the cleared book balance at completion time; null while DRAFT. */
    @Column(name = "cleared_book_balance", precision = 19, scale = 4)
    @Setter
    private BigDecimal clearedBookBalance;

    /**
     * Reconciling difference at completion: statementClosingBalance − clearedBookBalance.
     * Zero when balanced (the normal case); non-zero indicates outstanding items not yet
     * cleared. Persisted at completion alongside clearedBookBalance. Null while DRAFT.
     */
    @Column(name = "unreconciled_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal unreconciledAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Setter
    private ReconciliationStatus status = ReconciliationStatus.DRAFT;

    @Column(name = "reconciled_by")
    @Setter
    private Long reconciledBy;

    @Column(name = "completed_at")
    @Setter
    private Instant completedAt;

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

    protected BankReconciliation() {
        // JPA
    }

    public BankReconciliation(Long companyId, Long branchId, Long cashBankAccountId,
                               String reconciliationNumber, LocalDate statementDate,
                               BigDecimal statementOpeningBalance,
                               BigDecimal statementClosingBalance, Long createdBy) {
        this.companyId                = companyId;
        this.branchId                 = branchId;
        this.cashBankAccountId        = cashBankAccountId;
        this.reconciliationNumber     = reconciliationNumber;
        this.statementDate            = statementDate;
        this.statementOpeningBalance  = statementOpeningBalance;
        this.statementClosingBalance  = statementClosingBalance;
        this.createdBy                = createdBy;
    }
}
