package com.erp.modules.ar.domain.entity;

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
 * A bad-debt write-off. Closes the open item; posts DR Bad-Debt Expense / CR AR (ADR-0014 D-2e).
 */
@Getter
@Entity
@Table(name = "ar_write_offs")
public class ArWriteOff extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "ar_invoice_id", nullable = false, updatable = false)
    private Long arInvoiceId;

    @Column(name = "write_off_date", nullable = false, updatable = false)
    private LocalDate writeOffDate;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    /** Scalar uid of the GL journal entry (no FK — cross-module link, ADR-0014 D-11). */
    @Column(name = "gl_entry_uid", length = 26)
    @Setter
    private String glEntryUid;

    // -------------------------------------------------------------------------
    // P3 — FX capture for foreign-currency write-offs (base relievable off
    // ar_invoices.base_outstanding_amount). Additive columns; DEFAULT 1 / nullable.
    // -------------------------------------------------------------------------

    /** P3: settlement rate (units of base per 1 foreign unit) at write-off; immutable. DEFAULT 1. */
    @Column(name = "fx_rate", nullable = false, precision = 19, scale = 8, updatable = false)
    @Setter
    private BigDecimal fxRate = BigDecimal.ONE;

    /** P3: amount in base currency at {@link #fxRate}. Nullable for back-compat rows. */
    @Column(name = "base_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal baseAmount;

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

    protected ArWriteOff() {
        // JPA
    }

    public ArWriteOff(Long companyId, Long branchId, Long customerId, Long arInvoiceId,
                      LocalDate writeOffDate, BigDecimal amount, String currency,
                      String reason, Long createdBy) {
        this.companyId    = companyId;
        this.branchId     = branchId;
        this.customerId   = customerId;
        this.arInvoiceId  = arInvoiceId;
        this.writeOffDate = writeOffDate;
        this.amount       = amount;
        this.currency     = CurrencyCode.of(currency);
        this.reason       = reason;
        this.createdBy    = createdBy;
    }
}
