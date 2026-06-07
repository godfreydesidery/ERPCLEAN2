package com.erp.modules.products.domain.entity;

import com.erp.platform.common.domain.Ulid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A bulk-pack unit with a conversion factor to base (FR-PROD-06, ADR-0007 D-3).
 * e.g. a CARTON pack links to the CARTON UnitOfMeasure, factor_to_base = 24.
 * factor_to_base must be > 0 (BR-PROD-03, DB CHECK).
 * Does NOT extend {@link com.erp.platform.common.domain.UidEntity} — bulk packs are child records
 * without an optimistic-lock version column in the schema.
 */
@Getter
@Entity
@Table(name = "product_bulk_packs")
public class ProductBulkPack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, updatable = false, length = 26)
    private String uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    /**
     * FK → units_of_measure.id; the unit for this bulk pack (UoM cutover).
     * e.g. a CARTON pack links to the CARTON unit, factor_to_base = 24.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id", nullable = false)
    private UnitOfMeasure unit;

    /** Units of base per one pack; must be > 0 (DB CHECK). */
    @Column(name = "factor_to_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal factorToBase;

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

    @PrePersist
    void assignUid() {
        if (uid == null) {
            uid = Ulid.next();
        }
    }

    protected ProductBulkPack() {
        // JPA
    }

    public ProductBulkPack(Product product, UnitOfMeasure unit, BigDecimal factorToBase, Long createdBy) {
        this.product = product;
        this.unit = unit;
        this.factorToBase = factorToBase;
        this.createdBy = createdBy;
    }
}
