package com.erp.modules.tax.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/**
 * Output VAT breakdown by tax band — a frozen snapshot child of VatReturn (ADR-0017 D-2b).
 * Rebuilt on DRAFT recompute, frozen at FILE. No uid (addressed via parent; no ScopeGuard case).
 */
@Getter
@Entity
@Table(name = "vat_return_bands")
public class VatReturnBand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vat_return_id", nullable = false, updatable = false)
    private Long vatReturnId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "tax_band", nullable = false, length = 20)
    private String taxBand;

    @Column(name = "taxable_base", nullable = false)
    private BigDecimal taxableBase = BigDecimal.ZERO;

    @Column(name = "output_vat", nullable = false)
    private BigDecimal outputVat = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected VatReturnBand() {
        // JPA
    }

    public VatReturnBand(Long vatReturnId, Long companyId, String taxBand,
                         BigDecimal taxableBase, BigDecimal outputVat, Long createdBy) {
        this.vatReturnId = vatReturnId;
        this.companyId   = companyId;
        this.taxBand     = taxBand;
        this.taxableBase = taxableBase != null ? taxableBase : BigDecimal.ZERO;
        this.outputVat   = outputVat   != null ? outputVat   : BigDecimal.ZERO;
        this.createdBy   = createdBy;
    }
}
