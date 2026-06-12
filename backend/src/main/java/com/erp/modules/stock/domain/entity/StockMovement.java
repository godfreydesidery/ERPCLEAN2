package com.erp.modules.stock.domain.entity;

import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.platform.common.domain.Ulid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Append-only stock movement ledger row (ADR-0010 D-4, BR-STOCK-06).
 *
 * <p>Immutable after creation: no {@code updated_*} columns, no update/delete methods.
 * Every path that changes stock funnels through {@link com.erp.modules.stock.service.StockPostingService}
 * which writes one movement row and updates {@link StockOnHand} in the same transaction.
 *
 * <p>{@code source_event_uid} is the idempotency traceability key for event-driven movements (D-4
 * D-6b); {@code null} for manual movements (ADJUSTMENT / OPENING_BALANCE). The
 * {@code uq_stock_movement_source_event (source_event_uid, product_id)} DB backstop ensures a given
 * (event, product) is deducted at most once.
 *
 * <p>No Lombok on entities (PROJECT-CONVENTIONS).
 */
@Entity
@Table(name = "stock_movements")
public class StockMovement {

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
     * Scalar FK to stock_locations.id — the physical location affected by this movement.
     * Added by ADR-0028 V38 re-grain; backfilled to the branch default location for existing rows.
     */
    @Column(name = "location_id", nullable = false, updatable = false)
    private Long locationId;

    /** Scalar FK to products.id — no cross-module @ManyToOne (D-1). */
    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 25, updatable = false)
    private MovementType movementType;

    /** Signed delta in base units. Non-zero (enforced by chk_stock_movement_qty). */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal quantity;

    /**
     * The domain_events.uid that drove this movement. Set for all event-driven types;
     * {@code null} for ADJUSTMENT / OPENING_BALANCE (no originating event).
     */
    @Column(name = "source_event_uid", length = 26, updatable = false)
    private String sourceEventUid;

    /** Originating aggregate kind (SALES_INVOICE / GOODS_RECEIPT). {@code null} for manual. */
    @Column(name = "source_document_type", length = 40, updatable = false)
    private String sourceDocumentType;

    /**
     * Originating aggregate uid (invoice uid / receipt uid). {@code null} for manual.
     * No FK — cross-module scalar reference (D-4).
     */
    @Column(name = "source_document_uid", length = 26, updatable = false)
    private String sourceDocumentUid;

    /**
     * Mandatory for ADJUSTMENT (checked by chk_stock_movement_reason + validated against
     * {@link com.erp.modules.stock.domain.enums.AdjustmentReason} by the service).
     * {@code null} for all other movement types.
     */
    @Column(name = "reason_code", length = 40, updatable = false)
    private String reasonCode;

    /** Optional free-text explanation; anomaly note for an unmatched reversal. */
    @Column(name = "note", length = 500, updatable = false)
    private String note;

    /**
     * Unit cost applied to this movement (ADR-0020 D-2): the receipt cost (GOODS_RECEIPT), the
     * current avg at issue (SALE_ISSUE / ADJUSTMENT-out), or the opening cost (OPENING_BALANCE).
     * NULL for movements that post no cost.  Immutable (append-only ledger).
     * CHECK (unit_cost_amount IS NULL AND value_amount IS NULL) OR unit_cost_amount >= 0.
     */
    @Column(name = "unit_cost_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal unitCostAmount;

    /**
     * Signed value of this movement = qty × unit_cost_amount, HALF_UP 4 dp (ADR-0020 D-2).
     * + for receipt / increase; − for issue / decrease.
     * This is what a reversal reverses at (D-5 exact-original-cost reversal).
     * Immutable (append-only ledger).
     */
    @Column(name = "value_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal valueAmount;

    // --- cost-centre (ADR-0025 D-6, V28) — dimension default carrier for ADJUSTMENT rows ---
    // Nullable. InventoryGlPoster.postAdjustmentDirect uses these to stamp the expense LineDraft.
    /** FK → dimension_values(id); Cost Centre dimension default for this adjustment (nullable). */
    @Column(name = "cost_centre_value_id", updatable = false)
    private Long costCentreValueId;

    /** FK → dimension_values(id); Department dimension default for this adjustment (nullable). */
    @Column(name = "department_value_id", updatable = false)
    private Long departmentValueId;

    /** When the movement occurred (business time, not DB insert time). */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Operator for manual movements; {@code null} for system/event-driven (D-9). */
    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected StockMovement() {
        // JPA
    }

    /**
     * Full constructor used by {@link com.erp.modules.stock.service.StockPostingService}.
     * The two trailing cost params are nullable — existing paths that post no cost pass null.
     *
     * @param companyId          tenant scope
     * @param branchId           tenant branch scope
     * @param locationId         the physical location affected (ADR-0028 D-3)
     * @param productId          the product whose on-hand this row changes
     * @param movementType       the signed movement category
     * @param quantity           signed delta in base units (non-zero)
     * @param sourceEventUid     the originating domain_events.uid (null for manual)
     * @param sourceDocumentType SALES_INVOICE / GOODS_RECEIPT (null for manual)
     * @param sourceDocumentUid  the invoice / receipt uid (null for manual)
     * @param reasonCode         AdjustmentReason name (mandatory for ADJUSTMENT, null otherwise)
     * @param note               optional free-text
     * @param occurredAt         business time of the movement
     * @param createdBy          operator id for manual movements; null for system
     * @param unitCostAmount     unit cost at this movement (ADR-0020 D-2); null = no cost
     * @param valueAmount        signed value = qty × unit_cost, HALF_UP 4dp; null = no cost
     */
    public StockMovement(Long companyId, Long branchId, Long locationId, Long productId,
                         MovementType movementType, BigDecimal quantity,
                         String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                         String reasonCode, String note,
                         Instant occurredAt, Long createdBy,
                         BigDecimal unitCostAmount, BigDecimal valueAmount) {
        this(companyId, branchId, locationId, productId, movementType, quantity,
             sourceEventUid, sourceDocumentType, sourceDocumentUid,
             reasonCode, note, occurredAt, createdBy, unitCostAmount, valueAmount,
             null, null);
    }

    /**
     * Extended constructor with dimension default ids (ADR-0025 D-6, V28) + locationId (ADR-0028 D-3).
     */
    public StockMovement(Long companyId, Long branchId, Long locationId, Long productId,
                         MovementType movementType, BigDecimal quantity,
                         String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                         String reasonCode, String note,
                         Instant occurredAt, Long createdBy,
                         BigDecimal unitCostAmount, BigDecimal valueAmount,
                         Long costCentreValueId, Long departmentValueId) {
        this.companyId           = companyId;
        this.branchId            = branchId;
        this.locationId          = locationId;
        this.productId           = productId;
        this.movementType        = movementType;
        this.quantity            = quantity;
        this.sourceEventUid      = sourceEventUid;
        this.sourceDocumentType  = sourceDocumentType;
        this.sourceDocumentUid   = sourceDocumentUid;
        this.reasonCode          = reasonCode;
        this.note                = note;
        this.occurredAt          = occurredAt != null ? occurredAt : Instant.now();
        this.createdBy           = createdBy;
        this.unitCostAmount      = unitCostAmount;
        this.valueAmount         = valueAmount;
        this.costCentreValueId   = costCentreValueId;
        this.departmentValueId   = departmentValueId;
    }

    /**
     * Legacy 14-arg constructor (no locationId) — retained so existing callers compile.
     * locationId defaults to null; the V38 migration backfills existing rows.
     * @deprecated Use the locationId-bearing constructors (ADR-0028 D-3).
     */
    @Deprecated
    public StockMovement(Long companyId, Long branchId, Long productId,
                         MovementType movementType, BigDecimal quantity,
                         String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                         String reasonCode, String note,
                         Instant occurredAt, Long createdBy,
                         BigDecimal unitCostAmount, BigDecimal valueAmount) {
        this(companyId, branchId, null, productId, movementType, quantity,
             sourceEventUid, sourceDocumentType, sourceDocumentUid,
             reasonCode, note, occurredAt, createdBy, unitCostAmount, valueAmount,
             null, null);
    }

    /**
     * Legacy 16-arg constructor (no locationId) — retained so ADR-0025 callers compile.
     * @deprecated Use the locationId-bearing constructors (ADR-0028 D-3).
     */
    @Deprecated
    public StockMovement(Long companyId, Long branchId, Long productId,
                         MovementType movementType, BigDecimal quantity,
                         String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                         String reasonCode, String note,
                         Instant occurredAt, Long createdBy,
                         BigDecimal unitCostAmount, BigDecimal valueAmount,
                         Long costCentreValueId, Long departmentValueId) {
        this(companyId, branchId, null, productId, movementType, quantity,
             sourceEventUid, sourceDocumentType, sourceDocumentUid,
             reasonCode, note, occurredAt, createdBy, unitCostAmount, valueAmount,
             costCentreValueId, departmentValueId);
    }

    @PrePersist
    void prePersist() {
        if (uid == null)       uid = Ulid.next();
        if (createdAt == null) createdAt = Instant.now();
        if (occurredAt == null) occurredAt = createdAt;
    }

    // -------------------------------------------------------------------------
    // Accessors (no setters — append-only)
    // -------------------------------------------------------------------------

    public Long         getId()                  { return id; }
    public String       getUid()                 { return uid; }
    public Long         getCompanyId()           { return companyId; }
    public Long         getBranchId()            { return branchId; }
    public Long         getLocationId()          { return locationId; }
    public Long         getProductId()           { return productId; }
    public MovementType getMovementType()        { return movementType; }
    public BigDecimal   getQuantity()            { return quantity; }
    public String       getSourceEventUid()      { return sourceEventUid; }
    public String       getSourceDocumentType()  { return sourceDocumentType; }
    public String       getSourceDocumentUid()   { return sourceDocumentUid; }
    public String       getReasonCode()          { return reasonCode; }
    public String       getNote()                { return note; }
    public Instant      getOccurredAt()          { return occurredAt; }
    public Instant      getCreatedAt()           { return createdAt; }
    public Long         getCreatedBy()           { return createdBy; }
    public BigDecimal   getUnitCostAmount()      { return unitCostAmount; }
    public BigDecimal   getValueAmount()         { return valueAmount; }
    // cost-centre (ADR-0025 D-6)
    public Long         getCostCentreValueId()   { return costCentreValueId; }
    public Long         getDepartmentValueId()   { return departmentValueId; }
}
