package com.erp.modules.products.domain.entity;

import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.Money;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Product master (FR-PROD-01, ADR-0007 D-2).
 * One product per company; type GOODS|SERVICE; cost is a Money pair (ADR-0005).
 * Extends {@link UidEntity} (id + uid + version).
 */
@Getter
@Entity
@Table(name = "products")
public class Product extends UidEntity {

    /** FK → companies.id; tenant scope; never updated (BR-PROD-02). */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** System-assigned per-company code, e.g. {@code PROD-0001}. Never user-editable. */
    @Column(name = "code", nullable = false, updatable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    @Setter
    private String name;

    @Column(name = "description", length = 500)
    @Setter
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ProductType type;

    /** May appear on a customer sale (FR-PROD-04). */
    @Column(name = "sellable", nullable = false)
    @Setter
    private boolean sellable = true;

    /**
     * Inventory is tracked (FR-PROD-04).
     * A SERVICE must have stockable=false — enforced by DB CHECK chk_product_service_stockable.
     */
    @Column(name = "stockable", nullable = false)
    @Setter
    private boolean stockable = true;

    /**
     * FK → units_of_measure.id; base unit of measure (D-3, FR-PROD-05, UoM cutover).
     * Loaded LAZY; use the convenience accessors on ProductDto for code/name.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "base_unit_id", nullable = false)
    @Setter
    private UnitOfMeasure baseUnit;

    /**
     * Cost price — null when not set (ADR-0005 D-1).
     * Both cost_amount/cost_currency null together, or both set; enforced by DB CHECK.
     */
    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "amount",   column = @Column(name = "cost_amount",   precision = 19, scale = 4)),
            @AttributeOverride(name = "currency", column = @Column(name = "cost_currency", length = 3))
    })
    @Setter
    private Money cost;

    /**
     * Lot/batch tracking enabled (V35, ADR-0028 D-7). When true, every stock movement for
     * this product must reference a batch/lot. Mutually exclusive with serialTracked
     * (DB CHECK chk_product_tracking_exclusive).
     */
    @Column(name = "lot_tracked", nullable = false)
    @Setter
    private boolean lotTracked = false;

    /**
     * Serial-number tracking enabled (V35, ADR-0028 D-7). When true, every stock movement
     * must reference a serial. Mutually exclusive with lotTracked.
     */
    @Column(name = "serial_tracked", nullable = false)
    @Setter
    private boolean serialTracked = false;

    /**
     * Expiry-date tracking enabled. When true, batch/lot records must carry an expiry date
     * and FEFO picking is applied. Only meaningful when lotTracked = true.
     */
    @Column(name = "expiry_tracked", nullable = false)
    @Setter
    private boolean expiryTracked = false;

    /**
     * VAT classification of this product (FR-SALES-10, ADR-0008 D-5a).
     * Default STANDARD — additive safe; existing rows become standard-rated.
     * Stored as VARCHAR(20) matching every other enum column.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "vat_status", nullable = false, length = 20)
    @Setter
    private VatStatus vatStatus = VatStatus.STANDARD;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

    /**
     * Optional product category string — used by promotion CATEGORY target (ADR-0029 D-6, OQ-SD-05).
     * Nullable; no new category master in v1.
     */
    @Column(name = "category", length = 60)
    @Setter
    private String category;

    // -------------------------------------------------------------------------
    // P2-M3 — descriptive / logistics attributes. All nullable, additive-safe.
    // -------------------------------------------------------------------------

    /** Product brand (P2-M3). Nullable. */
    @Column(name = "brand", length = 120)
    @Setter
    private String brand;

    /** Manufacturer name (P2-M3). Nullable. */
    @Column(name = "manufacturer", length = 160)
    @Setter
    private String manufacturer;

    /** Unit weight (P2-M3). Nullable. */
    @Column(name = "weight", precision = 19, scale = 6)
    @Setter
    private java.math.BigDecimal weight;

    /** Unit volume (P2-M3). Nullable. */
    @Column(name = "volume", precision = 19, scale = 6)
    @Setter
    private java.math.BigDecimal volume;

    /** Free-text dimensions, e.g. LxWxH (P2-M3). Nullable. */
    @Column(name = "dimensions", length = 80)
    @Setter
    private String dimensions;

    /** Harmonised-system customs code (P2-M3). Nullable. */
    @Column(name = "hs_code", length = 20)
    @Setter
    private String hsCode;

    // -------------------------------------------------------------------------
    // D-10 — Product planning + sourcing (ADR-0040)
    // All nullable — not required on initial creation. Quantities in base UoM.
    // -------------------------------------------------------------------------

    /** Minimum on-hand quantity that triggers a replenishment order (ADR-0040 D-10). */
    @Column(name = "reorder_level", precision = 19, scale = 6)
    @Setter
    private java.math.BigDecimal reorderLevel;

    /** Standard replenishment order quantity (ADR-0040 D-10). */
    @Column(name = "reorder_qty", precision = 19, scale = 6)
    @Setter
    private java.math.BigDecimal reorderQty;

    /** Safety buffer below which stock is considered critical (ADR-0040 D-10). */
    @Column(name = "safety_stock", precision = 19, scale = 6)
    @Setter
    private java.math.BigDecimal safetyStock;

    /** Minimum allowable on-hand quantity (ADR-0040 D-10). */
    @Column(name = "min_stock", precision = 19, scale = 6)
    @Setter
    private java.math.BigDecimal minStock;

    /** Maximum allowable on-hand quantity; must be >= min_stock when both set (ADR-0040 D-10). */
    @Column(name = "max_stock", precision = 19, scale = 6)
    @Setter
    private java.math.BigDecimal maxStock;

    /** Typical supplier lead time in calendar days (ADR-0040 D-10). */
    @Column(name = "lead_time_days")
    @Setter
    private Integer leadTimeDays;

    /** When false, product cannot appear on a purchase order line (ADR-0040 D-10). */
    @Column(name = "purchasable", nullable = false)
    @Setter
    private boolean purchasable = true;

    /**
     * Preferred supplier soft-FK (ADR-0040 D-10).
     * Intra-DB FK → suppliers(id). Service enforces same-company before setting.
     * Stored as scalar Long (not @ManyToOne) per cross-module soft-FK convention.
     */
    @Column(name = "preferred_supplier_id")
    @Setter
    private Long preferredSupplierId;

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

    protected Product() {
        // JPA
    }

    public Product(Long companyId, String code, String name, ProductType type,
                   boolean sellable, boolean stockable, UnitOfMeasure baseUnit, Long createdBy) {
        this.companyId = companyId;
        this.code = code;
        this.name = name;
        this.type = type;
        this.sellable = sellable;
        this.stockable = stockable;
        this.baseUnit = baseUnit;
        this.createdBy = createdBy;
    }

    /** Allow type to be set only at construction; not user-editable once set. */
    public void setType(ProductType type) {
        this.type = type;
    }
}
