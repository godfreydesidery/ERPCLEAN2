package com.erp.modules.fx.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Optional per-branch currency subset (ADR-0039 D-7).
 *
 * <p>When a branch has no rows here it <em>inherits</em> the full company allow-list and
 * the company default. When rows are present the branch is restricted to that subset.
 *
 * <p>Invariants (enforced in {@code CurrencyEnablementServiceImpl}):
 * <ul>
 *   <li>{@code currency_code} MUST be enabled and active in {@code company_currency}
 *       for the same company (subset rule — service-validated, ADR-0039 D-7).
 *   <li>Exactly one row per branch has {@code is_default = true} (partial-unique index).
 * </ul>
 *
 * <p>Default resolution order (ADR-0039 D-7): branch default → company default → company base.
 */
@Entity
@Table(name = "branch_currency")
public class BranchCurrency extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /**
     * ISO 4217 alpha-3 code — typed as {@link CurrencyCode} (ADR-0039 D-1).
     * Stored as {@code VARCHAR(3)} via the auto-applied {@code CurrencyCodeConverter}.
     */
    @Column(name = "currency_code", nullable = false, length = 3)
    private CurrencyCode currencyCode;

    /** True when this is the default document currency for the branch. */
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    /** Per-branch enablement toggle. */
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
    protected BranchCurrency() {}

    public BranchCurrency(Long companyId, Long branchId, CurrencyCode currencyCode,
                           boolean isDefault, boolean active, Long createdBy) {
        this.companyId    = companyId;
        this.branchId     = branchId;
        this.currencyCode = currencyCode;
        this.isDefault    = isDefault;
        this.active       = active;
        this.createdAt    = Instant.now();
        this.createdBy    = createdBy;
    }

    public Long         getCompanyId()    { return companyId; }
    public Long         getBranchId()     { return branchId; }
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
