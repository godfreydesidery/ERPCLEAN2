package com.erp.modules.cashbank.domain.entity;

import com.erp.modules.cashbank.domain.enums.PettyCashTxnType;
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
 * A single movement against a {@link PettyCashFund} (ADR-0050 D-7 PR-B). RECORD-ONLY: no GL leg is
 * posted this slice. {@link #glAccountId} captures the intended expense/funding account for a
 * future manual journal or the GL fast-follow; {@link #journalEntryRef} is reserved and always
 * {@code null} in this slice.
 *
 * <p>{@code amount} is the SIGNED transaction amount for a signed {@code ADJUSTMENT} (may be
 * negative to decrease the balance; the caller's signed delta is applied to the fund's balance via
 * {@link PettyCashFund#applyAdjustment} and persisted here as-is), and a positive magnitude for
 * {@code DISBURSEMENT}/{@code REPLENISHMENT} (direction implied by {@link #txnType}) — DB CHECK
 * {@code chk_petty_cash_txn_amount} enforces exactly that. {@link #balanceAfter} always carries the
 * signed effect regardless of type.
 */
@Getter
@Entity
@Table(name = "petty_cash_transactions")
public class PettyCashTransaction extends UidEntity {

    @Column(name = "petty_cash_fund_id", nullable = false, updatable = false)
    private Long pettyCashFundId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "txn_number", nullable = false, length = 30, updatable = false)
    private String txnNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "txn_type", nullable = false, length = 20, updatable = false)
    private PettyCashTxnType txnType;

    @Column(name = "txn_date", nullable = false, updatable = false)
    private LocalDate txnDate;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    /** The fund's balance immediately after this transaction. */
    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal balanceAfter;

    /** Reserved: the expense/funding GL account captured for a future manual journal / fast-follow. */
    @Column(name = "gl_account_id")
    private Long glAccountId;

    /** Reserved for the GL fast-follow; always {@code null} in this record-only slice. */
    @Column(name = "journal_entry_ref", length = 26)
    @Setter
    private String journalEntryRef;

    @Column(name = "reference", length = 120)
    @Setter
    private String reference;

    @Column(name = "description", length = 500)
    @Setter
    private String description;

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

    protected PettyCashTransaction() {
        // JPA
    }

    public PettyCashTransaction(Long pettyCashFundId, Long companyId, Long branchId, String txnNumber,
                                 PettyCashTxnType txnType, LocalDate txnDate, BigDecimal amount,
                                 BigDecimal balanceAfter, Long glAccountId, String reference,
                                 String description, Long createdBy) {
        this.pettyCashFundId = pettyCashFundId;
        this.companyId       = companyId;
        this.branchId        = branchId;
        this.txnNumber       = txnNumber;
        this.txnType         = txnType;
        this.txnDate         = txnDate;
        this.amount          = amount;
        this.balanceAfter    = balanceAfter;
        this.glAccountId     = glAccountId;
        this.reference       = reference;
        this.description     = description;
        this.createdBy       = createdBy;
    }
}
