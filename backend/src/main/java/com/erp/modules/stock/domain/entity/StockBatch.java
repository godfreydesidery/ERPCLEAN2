package com.erp.modules.stock.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Lot/batch on-hand record per {@code (company, branch, location, product, lot_number)} (ADR-0028 D-7).
 *
 * <p>Integrity invariant (BR-INVD-10): {@code Σ stock_batches.qty_on_hand} for
 * {@code (location, product)} equals the corresponding {@code stock_on_hand.quantity}.
 * The service maintains this invariant; concurrency is guarded by {@code @Version} (via UidEntity).
 *
 * <p>FEFO consumption: issues decrement lots ordered by {@code expiry_date ASC NULLS LAST, id ASC}.
 * ({@link com.erp.modules.stock.service.StockBatchServiceImpl#consume}).
 */
@Entity
@Table(name = "stock_batches")
public class StockBatch extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "location_id", nullable = false, updatable = false)
    private Long locationId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "lot_number", nullable = false, updatable = false, length = 60)
    private String lotNumber;

    @Column(name = "manufacture_date")
    private LocalDate manufactureDate;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /** On-hand quantity at this location for this lot. May be signed (overselling stance). */
    @Column(name = "qty_on_hand", nullable = false, precision = 19, scale = 6)
    private BigDecimal qtyOnHand = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected StockBatch() {
        // JPA
    }

    public StockBatch(Long companyId, Long branchId, Long locationId, Long productId,
                      String lotNumber, LocalDate manufactureDate, LocalDate expiryDate,
                      Long createdBy) {
        this.companyId       = companyId;
        this.branchId        = branchId;
        this.locationId      = locationId;
        this.productId       = productId;
        this.lotNumber       = lotNumber;
        this.manufactureDate = manufactureDate;
        this.expiryDate      = expiryDate;
        this.qtyOnHand       = BigDecimal.ZERO;
        this.createdBy       = createdBy;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Domain behaviour
    // -------------------------------------------------------------------------

    /** Apply a signed quantity delta to this lot's on-hand. May go negative (overselling stance). */
    public void applyDelta(BigDecimal delta, Long actorId) {
        this.qtyOnHand = this.qtyOnHand.add(delta);
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Long       getCompanyId()      { return companyId; }
    public Long       getBranchId()       { return branchId; }
    public Long       getLocationId()     { return locationId; }
    public Long       getProductId()      { return productId; }
    public String     getLotNumber()      { return lotNumber; }
    public LocalDate  getManufactureDate(){ return manufactureDate; }
    public LocalDate  getExpiryDate()     { return expiryDate; }
    public BigDecimal getQtyOnHand()      { return qtyOnHand; }
    public Instant    getCreatedAt()      { return createdAt; }
    public Long       getCreatedBy()      { return createdBy; }
    public Instant    getUpdatedAt()      { return updatedAt; }
    public Long       getUpdatedBy()      { return updatedBy; }
}
