package com.erp.modules.fx.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Per-company enabled-currency allow-list entry (ADR-0039 D-7).
 *
 * <p>Each row enables one {@link CurrencyCode} for use in documents raised under
 * {@code company_id}. The row with {@code is_default = true} identifies the
 * <em>default document currency</em> — the code pre-selected on new documents (distinct
 * from {@code Company.baseCurrency}, the immutable ledger currency).
 *
 * <p>Invariants (enforced in {@code CurrencyEnablementServiceImpl}):
 * <ul>
 *   <li>The company's {@code base_currency} MUST have a row here that is {@code active}.
 *   <li>The base currency row CANNOT be deactivated.
 *   <li>Exactly one row per company has {@code is_default = true} (partial-unique index).
 *   <li>The {@code is_default} row MUST be {@code active}.
 *   <li>{@code currency_code} MUST be globally {@code currencies.active} to be enabled.
 * </ul>
 *
 * <p>{@code currencyCode} is typed as {@link CurrencyCode}; {@link
 * com.erp.platform.common.money.CurrencyCodeConverter} auto-applies to map to/from
 * {@code CHAR(3)} (ADR-0039 D-1 — dogfooding the converter).
 */
@Entity
@Table(name = "company_currency")
public class CompanyCurrency extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /**
     * ISO 4217 alpha-3 code — typed as {@link CurrencyCode} (ADR-0039 D-1).
     * Stored as {@code VARCHAR(3)} via the auto-applied {@code CurrencyCodeConverter}.
     */
    @Column(name = "currency_code", nullable = false, length = 3)
    private CurrencyCode currencyCode;

    /** True when this is the default document currency for the company. */
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    /** Per-company enablement toggle. */
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
    protected CompanyCurrency() {}

    public CompanyCurrency(Long companyId, CurrencyCode currencyCode,
                            boolean isDefault, boolean active, Long createdBy) {
        this.companyId    = companyId;
        this.currencyCode = currencyCode;
        this.isDefault    = isDefault;
        this.active       = active;
        this.createdAt    = Instant.now();
        this.createdBy    = createdBy;
    }

    public Long         getCompanyId()    { return companyId; }
    public CurrencyCode getCurrencyCode() { return currencyCode; }
    public boolean      isDefault()       { return isDefault; }
    public boolean      isActive()        { return active; }
    public Instant      getCreatedAt()    { return createdAt; }
    public Long         getCreatedBy()    { return createdBy; }
    public Instant      getUpdatedAt()    { return updatedAt; }
    public Long         getUpdatedBy()    { return updatedBy; }

    public void setDefault(boolean isDefault)      { this.isDefault = isDefault; }
    public void setActive(boolean active)          { this.active = active; }
    public void setUpdatedAt(Instant updatedAt)    { this.updatedAt = updatedAt; }
    public void setUpdatedBy(Long updatedBy)       { this.updatedBy = updatedBy; }
}
