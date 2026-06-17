package com.erp.modules.cashbank.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.modules.cashbank.domain.enums.ChequeDirection;
import com.erp.modules.cashbank.domain.enums.ChequeStatus;
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
 * Cheque register entry — tracks a physical cheque from ISSUED to CLEARED or CANCELLED
 * (ADR-0016 D-2d/D-5, BR-CASH-12). The GL effect rides the payment the cheque settles.
 */
@Getter
@Entity
@Table(name = "cheques")
public class Cheque extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    private Long branchId;

    /** Must be a BANK account (FR-CASH-10; service-enforced). */
    @Column(name = "cash_bank_account_id", nullable = false, updatable = false)
    private Long cashBankAccountId;

    @Column(name = "cheque_number", nullable = false, length = 40, updatable = false)
    private String chequeNumber;

    @Column(name = "payee", nullable = false, length = 160)
    @Setter
    private String payee;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Column(name = "issue_date", nullable = false, updatable = false)
    private LocalDate issueDate;

    @Column(name = "value_date", nullable = false)
    @Setter
    private LocalDate valueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    @Setter
    private ChequeStatus status = ChequeStatus.ISSUED;

    /** Scalar ref to the ap_payments.uid this cheque settles (no FK). */
    @Column(name = "ap_payment_uid", length = 26)
    private String apPaymentUid;

    /** Scalar ref to the cash_transactions.uid of the settled movement (no FK). */
    @Column(name = "cash_transaction_uid", length = 26)
    private String cashTransactionUid;

    @Column(name = "cleared_at")
    @Setter
    private Instant clearedAt;

    @Column(name = "cancelled_at")
    @Setter
    private Instant cancelledAt;

    // -------------------------------------------------------------------------
    // D-9 — bidirectional cheque fields (ADR-0040 D-9)
    // -------------------------------------------------------------------------

    /** Direction: OUTBOUND (issued to supplier, AP side) or INBOUND (received from customer, AR side). */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 8)
    private ChequeDirection direction = ChequeDirection.OUTBOUND;

    /**
     * Scalar uid of the AR receipt this inbound cheque is linked to (soft-FK, no DB FK — cross-module).
     * Must be NULL when direction == OUTBOUND (enforced by chk_cheque_inbound_link).
     */
    @Column(name = "ar_receipt_uid", length = 26)
    @Setter
    private String arReceiptUid;

    /** Timestamp when the inbound cheque was deposited to the bank (DEPOSITED transition). */
    @Column(name = "deposited_at")
    @Setter
    private Instant depositedAt;

    /** Timestamp when the cheque was returned unpaid (BOUNCED transition). */
    @Column(name = "bounced_at")
    @Setter
    private Instant bouncedAt;

    /** Free-text reason from the bank when the cheque bounced. */
    @Column(name = "bounce_reason", length = 160)
    @Setter
    private String bounceReason;

    /**
     * Number of times this cheque has been re-presented after bouncing.
     * Incremented by the deposit transition when bounced_at is already set.
     */
    @Column(name = "represent_count", nullable = false)
    @Setter
    private short representCount = 0;

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

    protected Cheque() {
        // JPA
    }

    /** Primary constructor for OUTBOUND cheques (AP payments). */
    public Cheque(Long companyId, Long branchId, Long cashBankAccountId,
                  String chequeNumber, String payee, BigDecimal amount, String currency,
                  LocalDate issueDate, LocalDate valueDate,
                  String apPaymentUid, String cashTransactionUid, Long createdBy) {
        this.companyId           = companyId;
        this.branchId            = branchId;
        this.cashBankAccountId   = cashBankAccountId;
        this.chequeNumber        = chequeNumber;
        this.payee               = payee;
        this.amount              = amount;
        this.currency            = CurrencyCode.ofNullable(currency);
        this.issueDate           = issueDate;
        this.valueDate           = valueDate;
        this.apPaymentUid        = apPaymentUid;
        this.cashTransactionUid  = cashTransactionUid;
        this.createdBy           = createdBy;
        this.direction           = ChequeDirection.OUTBOUND;
    }

    /** Constructor for INBOUND cheques (AR receipts by cheque). */
    public Cheque(Long companyId, Long branchId, Long cashBankAccountId,
                  String chequeNumber, String payee, BigDecimal amount, String currency,
                  LocalDate issueDate, LocalDate valueDate,
                  String arReceiptUid, Long createdBy) {
        this.companyId           = companyId;
        this.branchId            = branchId;
        this.cashBankAccountId   = cashBankAccountId;
        this.chequeNumber        = chequeNumber;
        this.payee               = payee;
        this.amount              = amount;
        this.currency            = CurrencyCode.ofNullable(currency);
        this.issueDate           = issueDate;
        this.valueDate           = valueDate;
        this.arReceiptUid        = arReceiptUid;
        this.createdBy           = createdBy;
        this.direction           = ChequeDirection.INBOUND;
    }

    public ChequeDirection getDirection() {
        return direction;
    }
}
