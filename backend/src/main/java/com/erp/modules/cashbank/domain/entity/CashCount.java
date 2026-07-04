package com.erp.modules.cashbank.domain.entity;

import com.erp.modules.cashbank.domain.enums.CashCountStatus;
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
 * End-of-day cash count against a CASH till (ADR-0050 D-7 PR-A / D-7.5).
 *
 * <p>Lifecycle: OPEN (expected derived from the till's book balance as-of {@code businessDate})
 * -&gt; COUNTED (denominations recorded, {@code countedAmount}/{@code varianceAmount} computed)
 * -&gt; RECONCILED (non-zero variance auto-posted to GL + a linked {@code DIRECT_ENTRY} cash_transaction
 * written so the cash-book and GL move together). Status transitions are hand-written
 * ({@link #markCounted}/{@link #markReconciled}) rather than plain setters so the related fields
 * move together and the guard lives in one place (PROJECT-CONVENTIONS §3).
 */
@Getter
@Entity
@Table(name = "cash_counts")
public class CashCount extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** FK -> cash_bank_accounts(id); the till counted (must be CASH type). */
    @Column(name = "cash_account_id", nullable = false, updatable = false)
    private Long cashAccountId;

    @Column(name = "count_number", nullable = false, length = 30, updatable = false)
    private String countNumber;

    /** app_users.id of the cashier who performed the count. */
    @Column(name = "counted_by", nullable = false, updatable = false)
    private Long countedBy;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    /** Derived: the till's book balance as-of {@link #businessDate}. Never hand-typed. */
    @Column(name = "expected_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal expectedAmount;

    /** Sum of denomination lines (denomination × quantity). */
    @Column(name = "counted_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal countedAmount = BigDecimal.ZERO;

    /** counted - expected (over &gt; 0 / short &lt; 0). */
    @Column(name = "variance_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal varianceAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CashCountStatus status;

    /** The posted GL variance journal entry uid; set only when reconcile posted a non-zero variance. */
    @Column(name = "journal_entry_ref", length = 26)
    private String journalEntryRef;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

    @Column(name = "counted_at")
    private Instant countedAt;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "reconciled_by")
    private Long reconciledBy;

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

    protected CashCount() {
        // JPA
    }

    public CashCount(Long companyId, Long branchId, Long cashAccountId, String countNumber,
                      Long countedBy, LocalDate businessDate, BigDecimal expectedAmount,
                      String currency, Long createdBy) {
        this.companyId      = companyId;
        this.branchId       = branchId;
        this.cashAccountId  = cashAccountId;
        this.countNumber    = countNumber;
        this.countedBy      = countedBy;
        this.businessDate   = businessDate;
        this.expectedAmount = expectedAmount;
        this.currency       = CurrencyCode.of(currency);
        this.status         = CashCountStatus.OPEN;
        this.createdBy      = createdBy;
    }

    // -------------------------------------------------------------------------
    // Domain behaviour (lifecycle)
    // -------------------------------------------------------------------------

    /** OPEN -&gt; COUNTED: record the denomination total and the derived variance. */
    public void markCounted(BigDecimal countedAmount, BigDecimal varianceAmount, Long actorId) {
        this.countedAmount  = countedAmount;
        this.varianceAmount = varianceAmount;
        this.status         = CashCountStatus.COUNTED;
        this.countedAt      = Instant.now();
        this.updatedAt      = Instant.now();
        this.updatedBy      = actorId;
    }

    /**
     * COUNTED -&gt; RECONCILED. {@code journalEntryRef} is null when the variance was zero
     * (nothing was posted).
     */
    public void markReconciled(String journalEntryRef, Long actorId) {
        this.journalEntryRef = journalEntryRef;
        this.status          = CashCountStatus.RECONCILED;
        this.reconciledAt    = Instant.now();
        this.reconciledBy    = actorId;
        this.updatedAt       = Instant.now();
        this.updatedBy       = actorId;
    }
}
