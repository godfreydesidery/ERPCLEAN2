package com.erp.modules.ar.domain.entity;

import com.erp.modules.ar.domain.enums.ArCreditNoteOrigin;
import com.erp.modules.ar.domain.enums.ArCreditNoteStatus;
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
 * A credit note that reduces a customer receivable (ADR-0014 D-2d, ADR-0040 D-6).
 *
 * <p>GL timing (D-6): raise posts FULL contra ONCE (DR Revenue/VAT / CR AR-control for the
 * note's base total). Applying to invoices posts nothing to GL except a realized-FX plug per
 * allocation when settlement_rate differs from invoice rate. Mirrors {@link ArReceipt}.
 */
@Getter
@Entity
@Table(name = "ar_credit_notes")
public class ArCreditNote extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "credit_note_number", nullable = false, length = 30, updatable = false)
    private String creditNoteNumber;

    @Column(name = "ar_invoice_id")
    private Long arInvoiceId;

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

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20, updatable = false)
    private ArCreditNoteOrigin origin;

    /** Scalar uid of the GL journal entry; null for SALE_VOID (GL already reversed). */
    @Column(name = "gl_entry_uid", length = 26)
    @Setter
    private String glEntryUid;

    // -------------------------------------------------------------------------
    // ADR-0040 D-6 — unapplied tracking (receipt parity)
    // -------------------------------------------------------------------------

    /**
     * Remaining unapplied face amount (document currency).
     * Starts == amount at raise; decremented by each allocation at apply.
     * Invariant: Σ allocated + unapplied_amount == amount (service-enforced).
     */
    @Column(name = "unapplied_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal unappliedAmount;

    /** Total amount in base currency at the CN's fx_rate. Set at raise; immutable thereafter. */
    @Column(name = "base_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal baseAmount;

    /**
     * Remaining unapplied amount in base currency.
     * Starts == base_amount at raise; decremented by base_allocated_amount at each allocation.
     */
    @Column(name = "base_unapplied_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal baseUnappliedAmount;

    /**
     * CN document rate (units of base per 1 foreign unit; immutable; DEFAULT 1).
     * Used as settlement_rate for each allocation (FX plug = invoice_rate vs this rate).
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
    private ArCreditNoteStatus status = ArCreditNoteStatus.UNAPPLIED;

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

    protected ArCreditNote() {
        // JPA
    }

    public ArCreditNote(Long companyId, Long branchId, Long customerId,
                        String creditNoteNumber, Long arInvoiceId, LocalDate noteDate,
                        BigDecimal amount, BigDecimal netAmount, BigDecimal vatAmount,
                        String currency, String reason, ArCreditNoteOrigin origin, Long createdBy) {
        this.companyId        = companyId;
        this.branchId         = branchId;
        this.customerId       = customerId;
        this.creditNoteNumber = creditNoteNumber;
        this.arInvoiceId      = arInvoiceId;
        this.noteDate         = noteDate;
        this.amount           = amount;
        this.netAmount        = netAmount;
        this.vatAmount        = vatAmount;
        this.currency         = CurrencyCode.of(currency);
        this.reason           = reason;
        this.origin           = origin;
        this.createdBy        = createdBy;
        // unapplied starts at full amount (status = UNAPPLIED by default)
        this.unappliedAmount  = amount;
    }
}
