package com.erp.modules.ar.domain.entity;

import com.erp.modules.ar.domain.enums.ArInvoiceSource;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
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
 * An AR open item — the customer sub-ledger detail behind GL 1200 (ADR-0014 D-2a).
 *
 * <p>Created by {@code ArSalePostedHandler} for a credit sale (source=SALE, no GL post) or by
 * {@code ArOpeningBalanceServiceImpl} at go-live (source=OPENING_BALANCE, GL post). Outstanding
 * is maintained down by receipts / credit notes / write-offs; status is derived from balance.
 */
@Getter
@Entity
@Table(name = "ar_invoices")
public class ArInvoice extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    @Setter
    private Long branchId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private ArInvoiceSource source;

    @Column(name = "source_invoice_uid", length = 26, updatable = false)
    private String sourceInvoiceUid;

    @Column(name = "document_no", length = 30)
    @Setter
    private String documentNo;

    @Column(name = "original_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal originalAmount;

    @Column(name = "outstanding_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal outstandingAmount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "invoice_date", nullable = false, updatable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date", nullable = false)
    @Setter
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private ArInvoiceStatus status = ArInvoiceStatus.OPEN;

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
    // ADR-0036 D-4 — FX base triple (V78). Stamped when the AR open item is created.
    // fx_rate + base_original_amount are immutable (BR-CUR-05).
    // base_outstanding_amount tracks the base value of the unpaid remainder (moves with outstanding).
    // -------------------------------------------------------------------------

    /** Rate at which this open item was originally booked (immutable; DEFAULT 1). */
    @Column(name = "fx_rate", nullable = false, precision = 19, scale = 8, updatable = false)
    @Setter
    private BigDecimal fxRate = BigDecimal.ONE;

    /** Original amount in base currency (immutable). NULL until the open item is stamped. */
    @Column(name = "base_original_amount", precision = 19, scale = 4, updatable = false)
    @Setter
    private BigDecimal baseOriginalAmount;

    /** Outstanding amount in base currency. Decremented when receipts / CN / write-offs reduce it. */
    @Column(name = "base_outstanding_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal baseOutstandingAmount;

    /** Timestamp when rate was stamped (immutable). */
    @Column(name = "rate_at", updatable = false)
    @Setter
    private Instant rateAt;

    protected ArInvoice() {
        // JPA
    }

    public ArInvoice(Long companyId, Long branchId, Long customerId,
                     ArInvoiceSource source, String sourceInvoiceUid, String documentNo,
                     BigDecimal amount, String currency,
                     LocalDate invoiceDate, LocalDate dueDate, Long createdBy) {
        this.companyId         = companyId;
        this.branchId          = branchId;
        this.customerId        = customerId;
        this.source            = source;
        this.sourceInvoiceUid  = sourceInvoiceUid;
        this.documentNo        = documentNo;
        this.originalAmount    = amount;
        this.outstandingAmount = amount;
        this.currency          = currency;
        this.invoiceDate       = invoiceDate;
        this.dueDate           = dueDate;
        this.createdBy         = createdBy;
    }
}
