package com.erp.modules.sales.domain.entity;

import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-company VAT rate master (ADR-0008 D-5b, FR-SALES-10).
 * One row per (company, vat_status). Rate is maintained data — never hard-coded.
 * Extends {@link UidEntity} (id + uid + @Version).
 */
@Getter
@Entity
@Table(name = "tax_rates")
public class TaxRate extends UidEntity {

    /** FK → companies.id; tenant scope; never updated. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** The VAT classification this rate applies to. Unique per (company, vat_status). */
    @Enumerated(EnumType.STRING)
    @Column(name = "vat_status", nullable = false, length = 20)
    private VatStatus vatStatus;

    /**
     * The rate value, e.g. 0.1800 = 18%. CHECK >= 0 AND < 1.
     * Stored as NUMERIC(9,4) matching the line snapshot column.
     */
    @Column(name = "rate", nullable = false, precision = 9, scale = 4)
    @Setter
    private BigDecimal rate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

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

    protected TaxRate() {
        // JPA
    }

    public TaxRate(Long companyId, VatStatus vatStatus, BigDecimal rate, Long createdBy) {
        this.companyId = companyId;
        this.vatStatus = vatStatus;
        this.rate = rate;
        this.createdBy = createdBy;
    }
}
