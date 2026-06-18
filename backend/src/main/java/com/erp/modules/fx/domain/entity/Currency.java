package com.erp.modules.fx.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Global currency master (ADR-0036 D-2, FR-CUR-01).
 *
 * <p>This is the ONE table that deliberately carries NO {@code company_id} — currency codes are
 * org-wide reference data, the same exception chart_of_account types enjoy. It is read-only to all
 * but {@code CURRENCY.MANAGE}. Every <em>other</em> new FX table carries {@code company_id} and
 * the tenant predicate.
 *
 * <p>{@code minor_units} drives HALF_UP rounding scale in {@link
 * com.erp.platform.common.money.CurrencyConversionService} (ADR-0036 D-8, ADR-0005 D-2/D-3).
 * Valid values: 0 (TZS, JPY), 2 (USD, EUR, KES, GBP), 3 (BHD).
 *
 * <p>No Lombok on JPA entities per PROJECT-CONVENTIONS. UidEntity provides {@code id},
 * {@code uid}, {@code @Version} via the base class.
 */
@Entity
@Table(name = "currencies")
public class Currency extends UidEntity {

    @Column(name = "code", nullable = false, updatable = false, length = 3)
    private String code;

    @Column(name = "name", nullable = false, length = 60)
    private String name;

    @Column(name = "symbol", length = 8)
    private String symbol;

    /** ISO 4217 minor units — drives HALF_UP rounding scale. Values: 0, 2, or 3. */
    @Column(name = "minor_units", nullable = false)
    private short minorUnits;

    /** ISO 4217 numeric code (e.g. "840" for USD, "834" for TZS); nullable (P2-M1). */
    @Column(name = "numeric_code", length = 3)
    private String numericCode;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    /**
     * Lifecycle status (X3). Kept as a String mapped to {@code MasterStatus} values
     * ('ACTIVE'/'INACTIVE', per {@code chk_currency_status}). NOT migrated to an
     * {@code @Enumerated MasterStatus} field because {@code getStatus()} flows into
     * {@code CurrencyDto.status} (String) via {@code FxRateServiceImpl.toCurrencyDto} in the
     * platform/common module — typing the field would force an out-of-cluster edit. The
     * {@code active} boolean is retained for back-compat (do NOT drop); the two are independent.
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    /** JPA no-arg constructor. */
    protected Currency() {}

    public Currency(String code, String name, String symbol, short minorUnits, Long createdBy) {
        this.code        = code;
        this.name        = name;
        this.symbol      = symbol;
        this.minorUnits  = minorUnits;
        this.active      = true;
        this.status      = "ACTIVE";
        this.createdAt   = Instant.now();
        this.createdBy   = createdBy;
    }

    public String getCode()         { return code; }
    public String getName()         { return name; }
    public String getSymbol()       { return symbol; }
    public short  getMinorUnits()   { return minorUnits; }
    public String getNumericCode()  { return numericCode; }
    public boolean isActive()       { return active; }
    public String getStatus()       { return status; }
    public Instant getCreatedAt()   { return createdAt; }
    public Long getCreatedBy()      { return createdBy; }
    public Instant getUpdatedAt()   { return updatedAt; }
    public Long getUpdatedBy()      { return updatedBy; }

    public void setName(String name)             { this.name = name; }
    public void setSymbol(String symbol)         { this.symbol = symbol; }
    public void setNumericCode(String numericCode) { this.numericCode = numericCode; }
    public void setActive(boolean active)        { this.active = active; }
    public void setStatus(String status)         { this.status = status; }
    public void setUpdatedAt(Instant updatedAt)  { this.updatedAt = updatedAt; }
    public void setUpdatedBy(Long updatedBy)     { this.updatedBy = updatedBy; }
}
