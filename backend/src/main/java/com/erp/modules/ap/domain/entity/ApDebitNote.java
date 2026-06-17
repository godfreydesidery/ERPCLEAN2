package com.erp.modules.ap.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * AP debit note — reduces an open payable (ADR-0015 D-2f).
 * Posts DR AP-control / CR Purchases[-or-VAT] synchronously (D-4/D-6).
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
    }
}
