package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.platform.common.domain.Ulid;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A tender taken against a sales invoice (ADR-0008 D-8, FR-SALES-17).
 *
 * <p>Does NOT extend {@link com.erp.platform.common.domain.UidEntity} — payments are add/remove
 * only (no @Version), mirroring ProductBarcode.
 * company_id and branch_id are DENORMALISED from the parent header at construction.
 */
@Getter
@Entity
@Table(name = "sales_invoice_payments")
public class SalesInvoicePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, updatable = false, length = 26)
    private String uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private SalesInvoice invoice;

    /** Denormalised from parent invoice; set at construction; immutable. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** Denormalised from parent invoice; set at construction; immutable. */
    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tender_type", nullable = false, length = 20)
    private TenderType tenderType;

    /** Tender amount in document currency; CHECK > 0. */
    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** Document currency; denormalised from parent. */
    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    /**
     * Cash over-tender returned as change (BR-SALES-07). NULL/0 for non-cash.
     * Effective tender = amount − change_amount. CHECK >= 0.
     */
    @Column(name = "change_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal changeAmount;

    /** Mobile-money / cash-drawer reference (optional). */
    @Column(name = "reference", length = 80)
    private String reference;

    // -------------------------------------------------------------------------
    // ADR-0041 D3 — instrument links + structured tender refs (all optional)
    // -------------------------------------------------------------------------

    /** Soft-FK to {@code cash_bank_accounts.id} the tender landed in (no DB FK — cross-module). */
    @Column(name = "cash_bank_account_id")
    @Setter
    private Long cashBankAccountId;

    /** Soft-FK to {@code cheques.id} when the tender is a cheque (no DB FK — cross-module). */
    @Column(name = "cheque_id")
    @Setter
    private Long chequeId;

    /** Structured mobile-money transaction reference (distinct from the free-text {@link #reference}). */
    @Column(name = "mobile_money_ref", length = 80)
    @Setter
    private String mobileMoneyRef;

    /** Structured card authorisation reference. */
    @Column(name = "card_ref", length = 80)
    @Setter
    private String cardRef;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    /** FK → app_users.id; the operator who took payment. */
    @Column(name = "received_by", nullable = false)
    private Long receivedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    void assignUid() {
        if (uid == null) {
            uid = Ulid.next();
        }
    }

    protected SalesInvoicePayment() {
        // JPA
    }

    public SalesInvoicePayment(SalesInvoice invoice, TenderType tenderType,
                               BigDecimal amount, String reference, Long receivedBy, Long createdBy) {
        this.invoice = invoice;
        this.companyId = invoice.getCompanyId();   // denormalise
        this.branchId = invoice.getBranchId();      // denormalise
        this.currency = invoice.getCurrency();      // denormalise
        this.tenderType = tenderType;
        this.amount = amount;
        this.reference = reference;
        this.receivedBy = receivedBy;
        this.createdBy = createdBy;
    }
}
