package com.erp.modules.ap.domain.entity;

import com.erp.modules.ap.domain.enums.ApPaymentKind;
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
 * AP payment — single or payment run (ADR-0015 D-2d).
 * One CR Cash/Bank GL post; settles one-or-many bills via ap_payment_allocations.
 */
@Getter
@Entity
@Table(name = "ap_payments")
public class ApPayment extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    @Setter
    private Long branchId;

    /** Payee supplier; NULL for a payment run spanning multiple suppliers. */
    @Column(name = "supplier_id")
    private Long supplierId;

    @Column(name = "payment_number", nullable = false, length = 30)
    private String paymentNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20, updatable = false)
    private ApPaymentKind kind;

    @Column(name = "payment_date", nullable = false, updatable = false)
    private LocalDate paymentDate;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Column(name = "tender_type", nullable = false, length = 20, updatable = false)
    private String tenderType;

    @Column(name = "bank_reference", length = 80)
    @Setter
    private String bankReference;

    /**
     * Captured beneficiary account uid (soft-FK to supplier_bank_accounts.uid, no DB FK — D-4).
     * Set at payment time; AP service validates ownership at that point.
     */
    @Column(name = "supplier_bank_account_uid", length = 26)
    @Setter
    private String supplierBankAccountUid;

    // -------------------------------------------------------------------------
    // D-7 — WHT withheld on payment header (ADR-0040 D-7)
    // -------------------------------------------------------------------------

    /** WHT amount withheld from this payment. Null when no WHT applies. Positive when set. */
    @Column(name = "wht_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal whtAmount;

    /**
     * Scalar uid of the wht_transactions row created at payment time (soft ref, no DB FK).
     * Set by ApPaymentServiceImpl after WhtCaptureService.captureOnPayment succeeds.
     */
    @Column(name = "wht_transaction_uid", length = 26)
    @Setter
    private String whtTransactionUid;

    @Column(name = "gl_entry_uid", length = 26)
    @Setter
    private String glEntryUid;

    /** FK → cash_bank_accounts(id); nullable for back-compat rows pre-V13 (ADR-0016 D-13). */
    @Column(name = "cash_bank_account_id")
    @Setter
    private Long cashBankAccountId;

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

    // -------------------------------------------------------------------------
    // ADR-0036 D-4 — FX settlement rate (V78). Stamped at payment creation; immutable.
    // The settlement rate drives the realized-FX leg in Tranche 3.
    // -------------------------------------------------------------------------

    /** Settlement rate (units of base per 1 foreign unit; immutable; DEFAULT 1). */
    @Column(name = "fx_rate", nullable = false, precision = 19, scale = 8, updatable = false)
    @Setter
    private BigDecimal fxRate = BigDecimal.ONE;

    /** Timestamp when the settlement rate was stamped (immutable). */
    @Column(name = "rate_at", updatable = false)
    @Setter
    private Instant rateAt;

    protected ApPayment() {
        // JPA
    }

    public ApPayment(Long companyId, Long branchId, Long supplierId,
                     String paymentNumber, ApPaymentKind kind, LocalDate paymentDate,
                     BigDecimal amount, String currency, String tenderType,
                     String bankReference, Long createdBy) {
        this.companyId      = companyId;
        this.branchId       = branchId;
        this.supplierId     = supplierId;
        this.paymentNumber  = paymentNumber;
        this.kind           = kind;
        this.paymentDate    = paymentDate;
        this.amount         = amount;
        this.currency       = CurrencyCode.of(currency);
        this.tenderType     = tenderType;
        this.bankReference  = bankReference;
        this.createdBy      = createdBy;
    }
}
