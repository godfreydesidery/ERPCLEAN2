package com.erp.modules.stock.domain.entity;

import com.erp.platform.common.domain.Ulid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Maintained current on-hand level per (company, branch, stockable product) (ADR-0010 D-2).
 *
 * <p>Not a {@link com.erp.platform.common.domain.UidEntity} master — it is a maintained projection
 * of the {@link StockMovement} ledger. A dedicated {@code @Version} field acts as the optimistic
 * lock guard against concurrent movements (NFR-STOCK-04, D-4).
 *
 * <p>Quantity is <em>signed</em>; it may go negative when overselling occurs (BR-STOCK-03). There is
 * deliberately no {@code >= 0} constraint — the DB CHECK is absent by design (D-2, ADR-0010).
 *
 * <p>No Lombok on entities (PROJECT-CONVENTIONS). Manual getters only.
 */
@Entity
@Table(name = "stock_on_hand")
public class StockOnHand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, updatable = false, length = 26)
    private String uid;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /**
     * Scalar FK to stock_locations.id — the physical location within the branch.
     * Added by ADR-0028 V38 re-grain; backfilled to the branch default location for existing rows.
     */
    @Column(name = "location_id", nullable = false, updatable = false)
    private Long locationId;

    /** Scalar FK to products.id — no cross-module @ManyToOne (D-1 boundary rule). */
    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    /** Signed on-hand quantity in base units. May be negative (overselling). DEFAULT 0. */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    private BigDecimal quantity = BigDecimal.ZERO;

    /** Optional per-(product, branch) reorder threshold. Indicator-only; NULL = no threshold. */
    @Column(name = "reorder_level", precision = 19, scale = 6)
    private BigDecimal reorderLevel;

    /** Optional max-stock indicator threshold (P2-M5). Indicator-only; NULL = no threshold. */
    @Column(name = "max_qty", precision = 19, scale = 6)
    private BigDecimal maxQty;

    /** Timestamp of the last applied movement (P2-M5 snapshot); NULL until first movement. */
    @Column(name = "last_movement_at")
    private Instant lastMovementAt;

    /** Timestamp of the last physical count (P2-M5 snapshot); NULL until first count. */
    @Column(name = "last_counted_at")
    private Instant lastCountedAt;

    /**
     * Running moving-average unit cost in base currency (ADR-0020 D-2).
     * NULL = no cost established yet (never received, never opened).
     * CHECK avg_cost IS NULL OR avg_cost >= 0 enforced by DB (chk_stock_on_hand_avg_nonneg).
     */
    @Column(name = "avg_cost", precision = 19, scale = 4)
    private BigDecimal avgCost;

    /**
     * Current on-hand inventory value = maintained running Σ of costed deltas (ADR-0020 D-2).
     * Authoritative: issues credit this exactly; the report sums it; avoids re-rounding loss.
     * NOT NULL DEFAULT 0.
     */
    @Column(name = "on_hand_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal onHandValue = BigDecimal.ZERO;

    /**
     * Aggregate soft-reserved quantity in base units across all confirmed SO lines for this
     * (company, branch, product). Available-to-promise = quantity − reserved_qty (ADR-0021 D-5).
     * NOT NULL DEFAULT 0. May not go below 0 (DB CHECK chk_stock_on_hand_reserved_nonneg).
     * Over-reservation: quantity − reserved_qty < 0 = negative available → backorder allowed.
     */
    @Column(name = "reserved_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal reservedQty = BigDecimal.ZERO;

    /** Optimistic lock — guards concurrent movement updates (NFR-STOCK-04). */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected StockOnHand() {
        // JPA
    }

    public StockOnHand(Long companyId, Long branchId, Long locationId, Long productId) {
        this.companyId   = companyId;
        this.branchId    = branchId;
        this.locationId  = locationId;
        this.productId   = productId;
        this.quantity    = BigDecimal.ZERO;
        this.reservedQty = BigDecimal.ZERO;
    }

    /** Legacy 3-arg constructor retained for backward compatibility; locationId defaults to null.
     *  @deprecated use the 4-arg constructor; callers must supply a locationId (ADR-0028 D-3). */
    @Deprecated
    public StockOnHand(Long companyId, Long branchId, Long productId) {
        this(companyId, branchId, null, productId);
    }

    @PrePersist
    void prePersist() {
        if (uid == null) uid = Ulid.next();
        if (createdAt == null) createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Domain behaviour
    // -------------------------------------------------------------------------

    /**
     * Apply a signed quantity delta to the on-hand level. May produce a negative value —
     * that is a designed, flagged state, not an error (BR-STOCK-03).
     */
    public void applyDelta(BigDecimal delta, Long actorId) {
        this.quantity   = this.quantity.add(delta);
        this.updatedAt  = Instant.now();
        this.updatedBy  = actorId; // null for system/event-driven movements (D-9)
    }

    /** Set or clear the optional reorder-level indicator (D-11). */
    public void setReorderLevel(BigDecimal level, Long actorId) {
        this.reorderLevel = level;
        this.updatedAt    = Instant.now();
        this.updatedBy    = actorId;
    }

    /**
     * Apply a cost recompute result (ADR-0020 D-2): update the running average and on-hand value.
     * Called by InventoryValuationServiceImpl under the @Version optimistic lock.
     *
     * @param newAvgCost    new moving-average unit cost (4 dp HALF_UP); may be null only if first state
     * @param newOnHandValue new authoritative on-hand value (4 dp HALF_UP)
     * @param actorId       null for system-event-driven paths
     */
    public void applyCostRecompute(BigDecimal newAvgCost, BigDecimal newOnHandValue, Long actorId) {
        this.avgCost      = newAvgCost;
        this.onHandValue  = newOnHandValue != null ? newOnHandValue : BigDecimal.ZERO;
        this.updatedAt    = Instant.now();
        this.updatedBy    = actorId;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /**
     * Apply a signed reservation delta (positive = reserve, negative = release).
     * Caller must ensure the result stays >= 0 (service enforces; DB CHECK backstop).
     */
    public void applyReservationDelta(BigDecimal delta, Long actorId) {
        this.reservedQty = this.reservedQty.add(delta);
        this.updatedAt   = Instant.now();
        this.updatedBy   = actorId;
    }

    /** Available-to-promise: signed, may be negative when over-reserved (backorder). */
    public BigDecimal availableQty() {
        return quantity.subtract(reservedQty);
    }

    public Long       getId()            { return id; }
    public String     getUid()           { return uid; }
    public Long       getCompanyId()     { return companyId; }
    public Long       getBranchId()      { return branchId; }
    public Long       getLocationId()    { return locationId; }
    public Long       getProductId()     { return productId; }
    public BigDecimal getQuantity()      { return quantity; }
    public BigDecimal getReorderLevel()  { return reorderLevel; }
    public BigDecimal getMaxQty()        { return maxQty; }
    public Instant    getLastMovementAt(){ return lastMovementAt; }
    public Instant    getLastCountedAt() { return lastCountedAt; }
    public BigDecimal getAvgCost()       { return avgCost; }
    public BigDecimal getOnHandValue()   { return onHandValue; }
    public BigDecimal getReservedQty()   { return reservedQty; }
    public Long       getVersion()       { return version; }
    public Instant    getCreatedAt()     { return createdAt; }
    public Long       getCreatedBy()     { return createdBy; }
    public Instant    getUpdatedAt()     { return updatedAt; }
    public Long       getUpdatedBy()     { return updatedBy; }
}
