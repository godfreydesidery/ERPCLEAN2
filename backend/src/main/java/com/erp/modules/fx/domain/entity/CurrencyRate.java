package com.erp.modules.fx.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Per-company, effective-dated exchange rate (ADR-0036 D-2).
 *
 * <h3>Rate direction convention (FIXED — applied everywhere, never inverted ad hoc)</h3>
 * <pre>
 *   rate = units of {@code to_currency} (base) per ONE unit of {@code from_currency} (foreign)
 *   base_amount = face_amount × rate
 * </pre>
 * Example: {@code from_currency=USD, to_currency=TZS, rate=2500.00000000}
 *          means 1 USD = TZS 2,500. A face amount of USD 100 × 2500 = TZS 250,000.
 *
 * <p>Rows are NEVER updated in place: a correction is a new effective-dated row, preserving
 * the full rate history (the audit trail). {@code updatable=false} on the rate/currency/date
 * columns enforces immutability after persist.
 *
 * <p>Strict per-company tenancy: every row carries {@code company_id} and the tenant predicate
 * (assertCanActIn on every read). No {@code company_id IS NULL} global rows in v1 (D-2).
 */
@Entity
@Table(name = "currency_rates")
public class CurrencyRate extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** Nullable — rates are company-level; carried for tenant predicate parity only. */
    @Column(name = "branch_id")
    private Long branchId;

    /** The foreign currency — face denomination. Soft FK → currencies.code. */
    @Column(name = "from_currency", nullable = false, updatable = false, length = 3)
    private String fromCurrency;

    /** Company base currency in v1; stored explicitly to allow cross-rates. Soft FK → currencies.code. */
    @Column(name = "to_currency", nullable = false, updatable = false, length = 3)
    private String toCurrency;

    /**
     * rate = units of to_currency per ONE unit of from_currency. Scale 8 (ADR-0005 D-5).
     * DB CHECK: rate > 0.
     */
    @Column(name = "rate", nullable = false, updatable = false, precision = 19, scale = 8)
    private BigDecimal rate;

    /** The as-of date for the effective-dated lookup. Immutable after persist. */
    @Column(name = "effective_date", nullable = false, updatable = false)
    private LocalDate effectiveDate;

    /**
     * P2 D7: end of this rate's validity window. NULL = still active (open-ended).
     * Mutable — set when a superseding rate closes this one.
     */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** Rate type: SPOT (default), CLOSING, AVERAGE, BUDGET. */
    @Column(name = "rate_type", nullable = false, length = 20)
    private String rateType = "SPOT";

    /** Optional: describes the rate source (manual entry, feed name, etc.). */
    @Column(name = "source", length = 40)
    private String source;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    /** JPA no-arg constructor. */
    protected CurrencyRate() {}

    public CurrencyRate(Long companyId, Long branchId,
                        String fromCurrency, String toCurrency,
                        BigDecimal rate, LocalDate effectiveDate,
                        String rateType, String source, Long createdBy) {
        this.companyId      = companyId;
        this.branchId       = branchId;
        this.fromCurrency   = fromCurrency;
        this.toCurrency     = toCurrency;
        this.rate           = rate;
        this.effectiveDate  = effectiveDate;
        this.rateType       = rateType != null ? rateType : "SPOT";
        this.source         = source;
        this.active         = true;
        this.createdAt      = Instant.now();
        this.createdBy      = createdBy;
    }

    public Long      getCompanyId()     { return companyId; }
    public Long      getBranchId()      { return branchId; }
    public String    getFromCurrency()  { return fromCurrency; }
    public String    getToCurrency()    { return toCurrency; }
    public BigDecimal getRate()         { return rate; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public LocalDate getEffectiveTo()   { return effectiveTo; }
    public String    getRateType()      { return rateType; }
    public String    getSource()        { return source; }
    public boolean   isActive()         { return active; }
    public Instant   getCreatedAt()     { return createdAt; }
    public Long      getCreatedBy()     { return createdBy; }
    public Instant   getUpdatedAt()     { return updatedAt; }
    public Long      getUpdatedBy()     { return updatedBy; }

    public void setActive(boolean active)        { this.active = active; }
    public void setUpdatedAt(Instant updatedAt)  { this.updatedAt = updatedAt; }
    public void setUpdatedBy(Long updatedBy)     { this.updatedBy = updatedBy; }
    public void setBranchId(Long branchId)       { this.branchId = branchId; }
    public void setSource(String source)         { this.source = source; }
    public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }
}
