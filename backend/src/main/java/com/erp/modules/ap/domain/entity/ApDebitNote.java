package com.erp.modules.ap.domain.entity;

import com.erp.modules.ap.domain.enums.ApDebitNoteStatus;
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
 * AP debit note — reduces an open payable (ADR-0015 D-2f, ADR-0041 D3).
 *
 * <p>GL timing (D3, parity with {@link com.erp.modules.ar.domain.entity.ArCreditNote}):
 * RAISE posts FULL contra ONCE (DR AP-control / CR Purchases [+ CR VAT_INPUT]) for the note's base
 * total at the DN's fx_rate. APPLY to bills posts nothing to GL except a realized-FX plug per
 * allocation when settlement_rate differs from bill rate.
 */
@Getter
@Entity
@Table(name = "ap_debit_notes")
public class ApDebitNote extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    @Setter
    private Long branchId;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private Long supplierId;

    @Column(name = "debit_note_number", nullable = false, length = 30)
    private String debitNoteNumber;

    /** The payable reduced — may be NULL for a general supplier credit. */
    @Column(name = "supplier_bill_id")
    @Setter
    private Long supplierBillId;

    @Column(name = "note_date", nullable = false, updatable = false)
    private LocalDate noteDate;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal netAmount;

    @Column(name = "vat_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal vatAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Column(name = "reason", nullable = false, length = 255, updatable = false)
    private String reason;

    @Column(name = "gl_entry_uid", length = 26)
    @Setter
    private String glEntryUid;

    /**
     * Free-text origin tag — populated by callers that raise debit notes on behalf of another document
     * (e.g. "PURCHASE_RETURN:{uid}" for ADR-0027 D-7, V35). NULL for manually raised notes.
     */
    @Column(name = "origin", length = 100)
    @Setter
    private String origin;

    /**
     * P2: source-document uid suffix, isolated from the combined {@code KIND:{uid}} value historically
     * carried in {@link #origin}. Lets the kind move to {@link com.erp.modules.ap.domain.enums.ApDebitNoteOrigin}
     * without losing the source-document reference. Nullable.
     */
    @Column(name = "origin_ref", length = 60)
    @Setter
    private String originRef;

    // -------------------------------------------------------------------------
    // ADR-0041 D3 — unapplied tracking (credit-note parity); see ArCreditNote
    // -------------------------------------------------------------------------

    /**
     * Remaining unapplied face amount (document currency).
     * Starts == amount at raise; decremented by each allocation at apply.
     * Invariant: Σ allocated + unapplied_amount == amount (service-enforced).
     */
    @Column(name = "unapplied_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal unappliedAmount;

    /** Total amount in base currency at the DN's fx_rate. Set at raise; immutable thereafter. */
    @Column(name = "base_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal baseAmount;

    /** Remaining unapplied amount in base currency. */
    @Column(name = "base_unapplied_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal baseUnappliedAmount;

    /**
     * DN document rate (units of base per 1 foreign unit; immutable; DEFAULT 1).
     * Used as settlement_rate for each allocation (FX plug = bill_rate vs this rate).
     */
    @Column(name = "fx_rate", nullable = false, precision = 19, scale = 8, updatable = false)
    @Setter
    private BigDecimal fxRate = BigDecimal.ONE;

    /** Timestamp when the rate was stamped (immutable). */
    @Column(name = "rate_at", updatable = false)
    @Setter
    private Instant rateAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private ApDebitNoteStatus status = ApDebitNoteStatus.UNAPPLIED;

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

    protected ApDebitNote() {
        // JPA
    }

    public ApDebitNote(Long companyId, Long branchId, Long supplierId,
                       String debitNoteNumber, Long supplierBillId,
                       LocalDate noteDate, BigDecimal amount, BigDecimal netAmount,
                       BigDecimal vatAmount, String currency, String reason, Long createdBy) {
        this.companyId       = companyId;
        this.branchId        = branchId;
        this.supplierId      = supplierId;
        this.debitNoteNumber = debitNoteNumber;
        this.supplierBillId  = supplierBillId;
        this.noteDate        = noteDate;
        this.amount          = amount;
        this.netAmount       = netAmount;
        this.vatAmount       = vatAmount;
        this.currency        = CurrencyCode.of(currency);
        this.reason          = reason;
        this.createdBy       = createdBy;
        // ADR-0041 D3: unapplied starts at full amount (status = UNAPPLIED by default)
        this.unappliedAmount = amount;
    }
}
