package com.erp.modules.products.domain.entity;

import com.erp.modules.products.domain.enums.BarcodeType;
import com.erp.platform.common.domain.Ulid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Barcode child of a product (FR-PROD-08, ADR-0007 D-5).
 * Does NOT extend {@link com.erp.platform.common.domain.UidEntity} — barcode rows are
 * add/remove only, so they have no {@code @Version} optimistic-lock column (brief §2.5).
 * They DO carry a {@code uid} for external addressing.
 * company_id is DENORMALISED from the product for the per-company uniqueness constraint
 * and the POS scan index — set once at write time, immutable (BR-PROD-07, NFR-PROD-01).
 */
@Getter
@Entity
@Table(name = "product_barcodes")
public class ProductBarcode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, updatable = false, length = 26)
    private String uid;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    /**
     * Denormalised company_id — set from product.companyId at create; immutable.
     * Anchor for uq_product_barcode_company_value and the POS lookup index.
     */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "barcode", nullable = false, length = 64)
    private String barcode;

    /**
     * Barcode symbology (P2-M3). Default OTHER — additive-safe; existing rows map to OTHER
     * via the DB DEFAULT.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "barcode_type", nullable = false, length = 20)
    @Setter
    private BarcodeType barcodeType = BarcodeType.OTHER;

    /** Optional unit-of-measure this barcode addresses (P2-M3). Scalar soft-FK → units_of_measure.id. */
    @Column(name = "uom_id")
    @Setter
    private Long uomId;

    /** At most one primary per product; enforced by partial unique index uq_product_barcode_primary. */
    @Column(name = "is_primary", nullable = false)
    @Setter
    private boolean primary = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @PrePersist
    void assignUid() {
        if (uid == null) {
            uid = Ulid.next();
        }
    }

    protected ProductBarcode() {
        // JPA
    }

    public ProductBarcode(Product product, String barcode, boolean primary, Long createdBy) {
        this.product = product;
        this.companyId = product.getCompanyId();  // denormalise at construction
        this.barcode = barcode;
        this.primary = primary;
        this.createdBy = createdBy;
    }
}
