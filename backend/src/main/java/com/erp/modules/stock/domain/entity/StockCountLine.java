package com.erp.modules.stock.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * A line item on a stock count document (ADR-0028 D-6, FR-INVD-12..17).
 *
 * <p>{@code system_qty} is the frozen snapshot; {@code counted_qty} is entered during COUNTING;
 * {@code variance_qty} and cost fields are computed at post time.
 *
 * <p>Extends {@link UidEntity} — carries {@code @Version} via the superclass.
 * No Lombok on entities.
 */
@Entity
@Table(name = "stock_count_lines")
public class StockCountLine extends UidEntity {

    @Column(name = "stock_count_id", nullable = false, updatable = false)
    private Long stockCountId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "line_no", nullable = false, updatable = false)
    private short lineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "product_code", nullable = false, updatable = false, length = 50)
    private String productCode;

    @Column(name = "product_name", nullable = false, updatable = false, length = 255)
    private String productName;

    @Column(name = "unit_id", updatable = false)
    private Long unitId;

    @Column(name = "unit_name", updatable = false, length = 50)
    private String unitName;

    /** Snapshot of system on-hand at freeze time. */
    @Column(name = "system_qty", nullable = false, updatable = false, precision = 19, scale = 6)
    private BigDecimal systemQty;

    /** Entered by the count team during COUNTING; NULL until entered. */
    @Column(name = "counted_qty", precision = 19, scale = 6)
    private BigDecimal countedQty;

    /** P2 D7: re-count quantity captured when a recount is performed. NULL if no recount. */
    @Column(name = "recounted_qty", precision = 19, scale = 6)
    private BigDecimal recountedQty;

    /** computed = counted − live-on-hand at post (BR-INVD-09, OQ-INVD-06). */
    @Column(name = "variance_qty", precision = 19, scale = 6)
    private BigDecimal varianceQty;

    /** The company-product avg_cost at post time. */
    @Column(name = "unit_cost_amount", precision = 19, scale = 4)
    private BigDecimal unitCostAmount;

    /** varianceQty × unitCostAmount, HALF_UP 4dp. */
    @Column(name = "variance_value", precision = 19, scale = 4)
    private BigDecimal varianceValue;

    /** AdjustmentReason for material variances (FR-INVD-16). */
    @Column(name = "reason_code", length = 40)
    private String reasonCode;

    /** uid of the ADJUSTMENT stock_movement posted for this line. */
    @Column(name = "movement_uid", length = 26)
    private String movementUid;

    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected StockCountLine() {
        // JPA
    }

    public StockCountLine(Long stockCountId, Long companyId, Long branchId, short lineNo,
                          Long productId, String productCode, String productName,
                          Long unitId, String unitName,
                          BigDecimal systemQty, String currency, Long createdBy) {
        this.stockCountId = stockCountId;
        this.companyId    = companyId;
        this.branchId     = branchId;
        this.lineNo       = lineNo;
        this.productId    = productId;
        this.productCode  = productCode;
        this.productName  = productName;
        this.unitId       = unitId;
        this.unitName     = unitName;
        this.systemQty    = systemQty;
        this.currency     = CurrencyCode.ofNullable(currency);
        this.createdBy    = createdBy;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Domain behaviour
    // -------------------------------------------------------------------------

    /**
     * Record the counted quantity for this line during COUNTING.
     *
     * @param countedQty the counted quantity
     * @param reasonCode the count-team's reason for a variance (FR-INVD-16), or {@code null}/blank
     *                   if none was entered. persona UAT I5: a blank value leaves any existing
     *                   reasonCode untouched so a re-enter (e.g. correcting a typo'd quantity)
     *                   cannot silently clobber a reason already recorded.
     * @param actorId    the acting user
     */
    public void enterCount(BigDecimal countedQty, String reasonCode, Long actorId) {
        this.countedQty = countedQty;
        if (reasonCode != null && !reasonCode.isBlank()) {
            this.reasonCode = reasonCode;
        }
        this.updatedAt  = Instant.now();
        this.updatedBy  = actorId;
    }

    /** Record the computed post values. Called at count post time. */
    public void applyPost(BigDecimal varianceQty, BigDecimal unitCostAmount,
                          BigDecimal varianceValue, String reasonCode, String movementUid,
                          Long actorId) {
        this.varianceQty     = varianceQty;
        this.unitCostAmount  = unitCostAmount;
        this.varianceValue   = varianceValue;
        this.reasonCode      = reasonCode;
        this.movementUid     = movementUid;
        this.updatedAt       = Instant.now();
        this.updatedBy       = actorId;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Long       getStockCountId()   { return stockCountId; }
    public Long       getCompanyId()      { return companyId; }
    public Long       getBranchId()       { return branchId; }
    public short      getLineNo()         { return lineNo; }
    public Long       getProductId()      { return productId; }
    public String     getProductCode()    { return productCode; }
    public String     getProductName()    { return productName; }
    public Long       getUnitId()         { return unitId; }
    public String     getUnitName()       { return unitName; }
    public BigDecimal getSystemQty()      { return systemQty; }
    public BigDecimal getCountedQty()     { return countedQty; }
    public BigDecimal getRecountedQty()   { return recountedQty; }
    public BigDecimal getVarianceQty()    { return varianceQty; }

    public void setRecountedQty(BigDecimal recountedQty) { this.recountedQty = recountedQty; }
    public BigDecimal getUnitCostAmount() { return unitCostAmount; }
    public BigDecimal getVarianceValue()  { return varianceValue; }
    public String     getReasonCode()     { return reasonCode; }
    public String     getMovementUid()    { return movementUid; }
    public String     getCurrency()       { return CurrencyCode.value(currency); }
    public Instant    getCreatedAt()      { return createdAt; }
    public Long       getCreatedBy()      { return createdBy; }
    public Instant    getUpdatedAt()      { return updatedAt; }
    public Long       getUpdatedBy()      { return updatedBy; }
}
