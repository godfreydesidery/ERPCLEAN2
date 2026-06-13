package com.erp.modules.hr.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** PAYE band schedule header — effective-dated, append-only (ADR-0032 D-3). */
@Getter
@Entity
@Table(name = "paye_band_sets")
public class PayeBandSet extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private LocalDate effectiveFrom;

    @Column(name = "tax_free_threshold", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal taxFreeThreshold;

    @Column(name = "description", length = 160)
    @Setter
    private String description;

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

    protected PayeBandSet() {}

    public PayeBandSet(Long companyId, LocalDate effectiveFrom,
                       BigDecimal taxFreeThreshold, String description, Long createdBy) {
        this.companyId        = companyId;
        this.effectiveFrom    = effectiveFrom;
        this.taxFreeThreshold = taxFreeThreshold;
        this.description      = description;
        this.createdBy        = createdBy;
    }
}
