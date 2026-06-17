package com.erp.modules.cashbank.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * Inter-account transfer header — CBT-#### — owning two cash_transactions + one GL entry
 * (ADR-0016 D-2c/D-4, BR-CASH-04).
 */
@Getter
@Entity
@Table(name = "cash_transfers")
public class CashTransfer extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "transfer_number", nullable = false, length = 30, updatable = false)
    private String transferNumber;

    @Column(name = "source_account_id", nullable = false, updatable = false)
    private Long sourceAccountId;

    @Column(name = "destination_account_id", nullable = false, updatable = false)
    private Long destinationAccountId;

    @Column(name = "transfer_date", nullable = false, updatable = false)
    private LocalDate transferDate;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Column(name = "reference", length = 255)
    private String reference;

    /** FK → cash_transactions(id) — the source OUT row. */
    @Column(name = "out_txn_id", nullable = false, updatable = false)
    private Long outTxnId;

    /** FK → cash_transactions(id) — the destination IN row. */
    @Column(name = "in_txn_id", nullable = false, updatable = false)
    private Long inTxnId;

    /** Scalar uid of the balanced GL entry (no FK — cross-module). */
    @Column(name = "journal_entry_ref", length = 26)
    @Setter
    private String journalEntryRef;

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

    protected CashTransfer() {
        // JPA
    }

    public CashTransfer(Long companyId, Long branchId, String transferNumber,
                         Long sourceAccountId, Long destinationAccountId,
                         LocalDate transferDate, BigDecimal amount, String currency,
                         String reference, Long outTxnId, Long inTxnId, Long createdBy) {
        this.companyId            = companyId;
        this.branchId             = branchId;
        this.transferNumber       = transferNumber;
        this.sourceAccountId      = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.transferDate         = transferDate;
        this.amount               = amount;
        this.currency             = CurrencyCode.ofNullable(currency);
        this.reference            = reference;
        this.outTxnId             = outTxnId;
        this.inTxnId              = inTxnId;
        this.createdBy            = createdBy;
    }
}
