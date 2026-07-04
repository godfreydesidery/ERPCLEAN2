package com.erp.modules.stock.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * A per-product line on a {@link VanReconciliation} worksheet (ADR-0051 D-8).
 *
 * <p>{@code loaded_qty}/{@code returned_qty} are derived from the transfer ledger (prefilled,
 * editable — {@link #applyDerived}); {@code sold_qty}/{@code physical_qty} are entered by the agent
 * ({@link #enterCounts}). {@code expected_qty = loaded − sold − returned} and
 * {@code variance_qty = physical − expected} are recomputed on every change and stored as
 * <strong>separate</strong> columns (never conflated).
 *
 * <p>Not a {@link com.erp.platform.common.domain.UidEntity} — mirrors {@code StockMovement} /
 * {@code StockOnHand}: an own numeric {@code @Id}, no {@code uid}, no {@code @Version} (the DDL for
 * {@code van_reconciliation_lines} carries neither column; ADR-0051 §D-8.5).
 *
 * <p>No Lombok on entities (PROJECT-CONVENTIONS).
 */
@Entity
@Table(name = "van_reconciliation_lines")
public class VanReconciliationLine {

    private static final int         VALUE_SCALE = 4;
    private static final RoundingMode VALUE_RM   = RoundingMode.HALF_UP;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "van_reconciliation_id", nullable = false, updatable = false)
    private Long vanReconciliationId;

    @Column(name = "line_no", nullable = false, updatable = false)
    private short lineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    /** Denormalised snapshot (immutability), mirrors StockCountLine. */
    @Column(name = "product_code", updatable = false, length = 60)
    private String productCode;

    @Column(name = "product_name", updatable = false, length = 200)
    private String productName;

    /** Derived — Σ TRANSFER_IN at the van in the business-date window; editable (agent override). */
    @Column(name = "loaded_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal loadedQty = BigDecimal.ZERO;

    /** Entered — the round's sold total for this product (not derivable; ADR-0051 Context). */
    @Column(name = "sold_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal soldQty = BigDecimal.ZERO;

    /** Derived — −Σ TRANSFER_OUT at the van in the business-date window; editable (agent override). */
    @Column(name = "returned_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal returnedQty = BigDecimal.ZERO;

    /** Computed = loaded − sold − returned. Stored (immutable snapshot per recompute). */
    @Column(name = "expected_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal expectedQty = BigDecimal.ZERO;

    /** Entered — the physical day-end count on the van. NULL until entered. */
    @Column(name = "physical_qty", precision = 19, scale = 6)
    private BigDecimal physicalQty;

    /** Computed = physical − expected. Zero (not null) until physical_qty is entered. */
    @Column(name = "variance_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal varianceQty = BigDecimal.ZERO;

    /** Snapshot avg cost at create time (report only — no GL, ADR-0051 D-8.2). */
    @Column(name = "unit_cost_amount", precision = 19, scale = 4)
    private BigDecimal unitCostAmount;

    /** variance_qty x unit_cost_amount, HALF_UP 4dp. NULL when unit_cost_amount is unknown. */
    @Column(name = "variance_value", precision = 19, scale = 4)
    private BigDecimal varianceValue;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected VanReconciliationLine() {
        // JPA
    }

    public VanReconciliationLine(Long vanReconciliationId, short lineNo, Long productId,
                                 String productCode, String productName, Long createdBy) {
        this.vanReconciliationId = vanReconciliationId;
        this.lineNo              = lineNo;
        this.productId           = productId;
        this.productCode         = productCode;
        this.productName         = productName;
        this.loadedQty           = BigDecimal.ZERO;
        this.soldQty             = BigDecimal.ZERO;
        this.returnedQty         = BigDecimal.ZERO;
        this.expectedQty         = BigDecimal.ZERO;
        this.varianceQty         = BigDecimal.ZERO;
        this.createdBy           = createdBy;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Domain behaviour
    // -------------------------------------------------------------------------

    /**
     * Apply the derived (or agent-overridden) loaded/returned quantities and the unit-cost
     * snapshot, then recompute expected/variance. Called at create time (from the transfer-ledger
     * derivation) and again at {@code enterLines} time if the caller supplies an override.
     */
    public void applyDerived(BigDecimal loadedQty, BigDecimal returnedQty, BigDecimal unitCostAmount) {
        this.loadedQty      = loadedQty != null ? loadedQty : BigDecimal.ZERO;
        this.returnedQty    = returnedQty != null ? returnedQty : BigDecimal.ZERO;
        this.unitCostAmount = unitCostAmount;
        recompute();
    }

    /**
     * Enter the round's sold total and the physical day-end count, then recompute
     * expected/variance/variance_value.
     *
     * <p>{@code physicalQty} is OPTIONAL (backend review fix): the agent may key {@code soldQty}
     * before doing the physical count. {@code sold} is always applied and {@code expected}
     * recomputed regardless; when {@code physicalQty} is {@code null}, {@code physical_qty} is
     * stored as {@code NULL} (column is nullable) and {@code variance_qty} is left at {@code ZERO}
     * — not {@code null} — because the column is {@code NOT NULL DEFAULT 0} (V85 DDL);
     * {@code variance_value} is left {@code NULL} (nullable column) since a variance is not yet
     * meaningful without a physical count. Both are recomputed for real once a later call supplies
     * {@code physicalQty}.
     */
    public void enterCounts(BigDecimal soldQty, BigDecimal physicalQty, Long actorId) {
        this.soldQty     = soldQty != null ? soldQty : BigDecimal.ZERO;
        this.physicalQty = physicalQty;
        recompute();
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }

    private void recompute() {
        this.expectedQty = this.loadedQty.subtract(this.soldQty).subtract(this.returnedQty);
        if (this.physicalQty == null) {
            this.varianceQty   = BigDecimal.ZERO;
            this.varianceValue = null;
            return;
        }
        this.varianceQty   = this.physicalQty.subtract(this.expectedQty);
        this.varianceValue = this.unitCostAmount != null
                ? this.varianceQty.multiply(this.unitCostAmount).setScale(VALUE_SCALE, VALUE_RM)
                : null;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Long       getId()                  { return id; }
    public Long       getVanReconciliationId()  { return vanReconciliationId; }
    public short      getLineNo()               { return lineNo; }
    public Long       getProductId()            { return productId; }
    public String     getProductCode()          { return productCode; }
    public String     getProductName()          { return productName; }
    public BigDecimal getLoadedQty()            { return loadedQty; }
    public BigDecimal getSoldQty()               { return soldQty; }
    public BigDecimal getReturnedQty()          { return returnedQty; }
    public BigDecimal getExpectedQty()          { return expectedQty; }
    public BigDecimal getPhysicalQty()          { return physicalQty; }
    public BigDecimal getVarianceQty()          { return varianceQty; }
    public BigDecimal getUnitCostAmount()       { return unitCostAmount; }
    public BigDecimal getVarianceValue()        { return varianceValue; }
    public Instant    getCreatedAt()            { return createdAt; }
    public Long       getCreatedBy()            { return createdBy; }
    public Instant    getUpdatedAt()            { return updatedAt; }
    public Long       getUpdatedBy()            { return updatedBy; }
}
