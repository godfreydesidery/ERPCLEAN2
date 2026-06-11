package com.erp.modules.products.domain.entity;

import com.erp.modules.products.domain.enums.BomStatus;
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
 * Versioned BOM header for a parent product (ADR-0026 D-2, FR-BOM-01..05).
 *
 * <p>A parent product may have many {@code Bom} rows (versions 1, 2, 3 …); at most one is
 * {@link BomStatus#ACTIVE} at any moment (BR-BOM-04 — enforced by the partial-unique index
 * {@code uq_bom_one_active} and the service's atomic supersede).
 *
 * <p>An ACTIVE version's component set is frozen (BR-BOM-03); notes/metadata are editable.
 * The full component list is loaded via {@link com.erp.modules.products.repository.BomComponentRepository}
 * rather than a Hibernate association — the explosion reads components in batches per level to
 * avoid N+1 (NFR-BOM-02, ADR-0026 D-6).
 */
@Getter
@Entity
@Table(name = "boms")
public class Bom extends UidEntity {

    /** Tenant; matches the parent product's company. Never updated (FR-BOM-19). */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** The product this BOM produces — scalar FK, never updated. */
    @Column(name = "parent_product_id", nullable = false, updatable = false)
    private Long parentProductId;

    /** 1, 2, 3 … per parent; allocated by the service (D-12). */
    @Column(name = "version_no", nullable = false, updatable = false)
    private Integer versionNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private BomStatus status = BomStatus.DRAFT;

    /** How many parent base-units one execution of this BOM produces (FR-BOM-05, BR-BOM-06). */
    @Column(name = "output_qty", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal outputQty = BigDecimal.ONE;

    /**
     * Header yield %; 100 = no loss (BR-BOM-08).
     * Applied as {@code ÷ yield_percent} during explosion (D-4).
     */
    @Column(name = "yield_percent", nullable = false, precision = 9, scale = 4)
    @Setter
    private BigDecimal yieldPercent = BigDecimal.valueOf(100);

    /** Start of effectivity window; set at activate (D-2). NULL while DRAFT. */
    @Column(name = "effective_from")
    @Setter
    private LocalDate effectiveFrom;

    /** End of effectivity window; NULL = open-ended/current. Closed at supersede (D-2). */
    @Column(name = "effective_to")
    @Setter
    private LocalDate effectiveTo;

    /** uid of the version this was cloned from, if any (OQ-BOM-06). */
    @Column(name = "source_bom_uid", length = 26)
    private String sourceBomUid;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

    @Column(name = "activated_at")
    @Setter
    private Instant activatedAt;

    @Column(name = "archived_at")
    @Setter
    private Instant archivedAt;

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

    protected Bom() {
        // JPA
    }

    public Bom(Long companyId, Long parentProductId, Integer versionNo, BigDecimal outputQty,
               BigDecimal yieldPercent, String notes, String sourceBomUid, Long createdBy) {
        this.companyId = companyId;
        this.parentProductId = parentProductId;
        this.versionNo = versionNo;
        this.outputQty = outputQty != null ? outputQty : BigDecimal.ONE;
        this.yieldPercent = yieldPercent != null ? yieldPercent : BigDecimal.valueOf(100);
        this.notes = notes;
        this.sourceBomUid = sourceBomUid;
        this.createdBy = createdBy;
    }
}
