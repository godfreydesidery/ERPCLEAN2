package com.erp.modules.manufacturing.domain.entity;

import com.erp.modules.manufacturing.domain.enums.WorkOrderStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Work-order header (ADR-0035 D-2).
 *
 * <p>Drives the PLANNED→RELEASED→IN_PROGRESS→COMPLETED→CLOSED lifecycle.
 * Holds the running WIP accumulators ({@code wip_debit_total} / {@code wip_credit_total})
 * that the WIP reconciliation compares to the WIP_INVENTORY GL balance (BR-MFG-05).
 *
 * <p>No Lombok on entities (PROJECT-CONVENTIONS). Manual getters + domain-behaviour methods.
 */
@Entity
@Table(name = "work_orders")
public class WorkOrder extends UidEntity {

    @Column(name = "wo_number", nullable = false, length = 30)
    private String woNumber;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false)
    private Long branchId;

    @Column(name = "finished_product_id", nullable = false, updatable = false)
    private Long finishedProductId;

    @Column(name = "finished_product_code", nullable = false, length = 20)
    private String finishedProductCode;

    @Column(name = "finished_product_name", nullable = false, length = 200)
    private String finishedProductName;

    /** Scalar FK → boms(id); NULL while PLANNED; stamped at release (BR-MFG-02). */
    @Column(name = "bom_id")
    private Long bomId;

    /** BOM uid snapshot for audit + explosion call. */
    @Column(name = "bom_uid", length = 26)
    private String bomUid;

    @Column(name = "planned_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal plannedQty;

    @Column(name = "good_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal goodQty = BigDecimal.ZERO;

    @Column(name = "scrap_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal scrapQty = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WorkOrderStatus status = WorkOrderStatus.PLANNED;

    /** Σ value debited to WIP (component issues + applied labour/overhead). */
    @Column(name = "wip_debit_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal wipDebitTotal = BigDecimal.ZERO;

    /** Σ value credited from WIP (finished receipts + variance-clear). */
    @Column(name = "wip_credit_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal wipCreditTotal = BigDecimal.ZERO;

    @Column(name = "labour_applied_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal labourAppliedTotal = BigDecimal.ZERO;

    @Column(name = "overhead_applied_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal overheadAppliedTotal = BigDecimal.ZERO;

    /** Finished-good unit cost at the last completion (WIP ÷ good qty). NULL until first completion. */
    @Column(name = "computed_unit_cost", precision = 19, scale = 4)
    private BigDecimal computedUnitCost;

    /** Residual WIP cleared to Manufacturing Variance at close; signed. */
    @Column(name = "variance_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal varianceAmount = BigDecimal.ZERO;

    /** true if any issued leaf had a NULL avg_cost (BR-MFG-06). */
    @Column(name = "incomplete_cost", nullable = false)
    private boolean incompleteCost = false;

    /** Optional cost-centre dimension tag (ADR-0025 D-9). Scalar FK → dimension_values(id). */
    @Column(name = "cost_centre_value_id")
    private Long costCentreValueId;

    /** Finished-goods receipt location (P2-M5). Soft-FK → stock_locations(id). */
    @Column(name = "target_location_id")
    private Long targetLocationId;

    @Column(name = "planned_date")
    private LocalDate plannedDate;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected WorkOrder() { /* JPA */ }

    public WorkOrder(Long companyId, Long branchId,
                     Long finishedProductId, String finishedProductCode, String finishedProductName,
                     BigDecimal plannedQty, String woNumber) {
        this.companyId            = companyId;
        this.branchId             = branchId;
        this.finishedProductId    = finishedProductId;
        this.finishedProductCode  = finishedProductCode;
        this.finishedProductName  = finishedProductName;
        this.plannedQty           = plannedQty;
        this.woNumber             = woNumber;
        this.createdAt            = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Domain behaviour
    // -------------------------------------------------------------------------

    public void release(Long bomId, String bomUid, Long actorId) {
        this.bomId      = bomId;
        this.bomUid     = bomUid;
        this.status     = WorkOrderStatus.RELEASED;
        this.releasedAt = Instant.now();
        touch(actorId);
    }

    public void startInProgress(Long actorId) {
        this.status = WorkOrderStatus.IN_PROGRESS;
        touch(actorId);
    }

    /** Accumulate a component-issue WIP debit. */
    public void accumulateWipDebit(BigDecimal value, Long actorId) {
        this.wipDebitTotal = this.wipDebitTotal.add(value);
        touch(actorId);
    }

    /** Accumulate applied labour/overhead WIP debit. */
    public void accumulateLabourOverhead(BigDecimal labour, BigDecimal overhead, Long actorId) {
        this.labourAppliedTotal  = this.labourAppliedTotal.add(labour);
        this.overheadAppliedTotal = this.overheadAppliedTotal.add(overhead);
        this.wipDebitTotal       = this.wipDebitTotal.add(labour).add(overhead);
        touch(actorId);
    }

    /** Record finished-goods receipt: relieve WIP + set computed unit cost. */
    public void recordCompletion(BigDecimal goodQty, BigDecimal scrapQty,
                                  BigDecimal computedUnitCost, BigDecimal relievedValue, Long actorId) {
        this.goodQty          = this.goodQty.add(goodQty);
        this.scrapQty         = this.scrapQty.add(scrapQty);
        this.computedUnitCost = computedUnitCost;
        this.wipCreditTotal   = this.wipCreditTotal.add(relievedValue);
        this.status           = WorkOrderStatus.COMPLETED;
        this.completedAt      = Instant.now();
        touch(actorId);
    }

    /** Close: record variance amount + WIP credit, set CLOSED. */
    public void close(BigDecimal residual, Long actorId) {
        this.varianceAmount = residual;
        this.wipCreditTotal = this.wipCreditTotal.add(residual);
        this.status         = WorkOrderStatus.CLOSED;
        this.closedAt       = Instant.now();
        touch(actorId);
    }

    /** Cancel: reset to CANCELLED. */
    public void cancel(Long actorId) {
        this.status      = WorkOrderStatus.CANCELLED;
        this.cancelledAt = Instant.now();
        touch(actorId);
    }

    /** Reverse a completion WIP credit (used during cancel). */
    public void reverseWipCredit(BigDecimal value, Long actorId) {
        this.wipCreditTotal = this.wipCreditTotal.subtract(value);
        touch(actorId);
    }

    /** Reverse a WIP debit (used during cancel for component issues or labour/overhead). */
    public void reverseWipDebit(BigDecimal value, Long actorId) {
        this.wipDebitTotal = this.wipDebitTotal.subtract(value);
        touch(actorId);
    }

    public void flagIncompleteCost() {
        this.incompleteCost = true;
    }

    /** The open WIP balance for this order = debit − credit. */
    public BigDecimal openWip() {
        return wipDebitTotal.subtract(wipCreditTotal);
    }

    private void touch(Long actorId) {
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }

    // -------------------------------------------------------------------------
    // Setters for PLANNED-only edits (BR-MFG-04)
    // -------------------------------------------------------------------------
    public void updateDraft(BigDecimal plannedQty, Long branchId,
                             LocalDate plannedDate, Long costCentreValueId, String notes, Long actorId) {
        this.plannedQty        = plannedQty;
        this.branchId          = branchId;
        this.plannedDate       = plannedDate;
        this.costCentreValueId = costCentreValueId;
        this.notes             = notes;
        touch(actorId);
    }

    public void pinBom(Long bomId, String bomUid, Long actorId) {
        this.bomId  = bomId;
        this.bomUid = bomUid;
        touch(actorId);
    }

    public void setCreatedBy(Long actorId) {
        this.createdBy = actorId;
    }

    /** Set the finished-goods receipt location (P2-M5). */
    public void setTargetLocationId(Long targetLocationId) {
        this.targetLocationId = targetLocationId;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------
    public String          getWoNumber()              { return woNumber; }
    public Long            getCompanyId()             { return companyId; }
    public Long            getBranchId()              { return branchId; }
    public Long            getFinishedProductId()     { return finishedProductId; }
    public String          getFinishedProductCode()   { return finishedProductCode; }
    public String          getFinishedProductName()   { return finishedProductName; }
    public Long            getBomId()                 { return bomId; }
    public String          getBomUid()                { return bomUid; }
    public BigDecimal      getPlannedQty()            { return plannedQty; }
    public BigDecimal      getGoodQty()               { return goodQty; }
    public BigDecimal      getScrapQty()              { return scrapQty; }
    public WorkOrderStatus getStatus()                { return status; }
    public BigDecimal      getWipDebitTotal()         { return wipDebitTotal; }
    public BigDecimal      getWipCreditTotal()        { return wipCreditTotal; }
    public BigDecimal      getLabourAppliedTotal()    { return labourAppliedTotal; }
    public BigDecimal      getOverheadAppliedTotal()  { return overheadAppliedTotal; }
    public BigDecimal      getComputedUnitCost()      { return computedUnitCost; }
    public BigDecimal      getVarianceAmount()        { return varianceAmount; }
    public boolean         isIncompleteCost()         { return incompleteCost; }
    public Long            getCostCentreValueId()     { return costCentreValueId; }
    public Long            getTargetLocationId()      { return targetLocationId; }
    public LocalDate       getPlannedDate()           { return plannedDate; }
    public Instant         getReleasedAt()            { return releasedAt; }
    public Instant         getCompletedAt()           { return completedAt; }
    public Instant         getClosedAt()              { return closedAt; }
    public Instant         getCancelledAt()           { return cancelledAt; }
    public String          getNotes()                 { return notes; }
    public Instant         getCreatedAt()             { return createdAt; }
    public Long            getCreatedBy()             { return createdBy; }
    public Instant         getUpdatedAt()             { return updatedAt; }
    public Long            getUpdatedBy()             { return updatedBy; }
}
