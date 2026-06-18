package com.erp.modules.manufacturing.domain.entity;

import com.erp.modules.manufacturing.domain.enums.ComponentLineStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-leaf component line on a work order (ADR-0035 D-3).
 *
 * <p>Materialised from the BOM explosion leaf summary at release (the plan).
 * Updated on issue (the actual). Carries the issued value for WIP accounting.
 * No Lombok on entities (PROJECT-CONVENTIONS).
 */
@Entity
@Table(name = "work_order_components")
public class WorkOrderComponent extends UidEntity {

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private Long workOrderId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "line_no", nullable = false)
    private Short lineNo;

    @Column(name = "component_product_id", nullable = false, updatable = false)
    private Long componentProductId;

    @Column(name = "component_product_code", nullable = false, length = 20)
    private String componentProductCode;

    @Column(name = "component_product_name", nullable = false, length = 200)
    private String componentProductName;

    @Column(name = "planned_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal plannedQty = BigDecimal.ZERO;

    @Column(name = "issued_qty", nullable = false, precision = 19, scale = 6)
    private BigDecimal issuedQty = BigDecimal.ZERO;

    /** Qty returned to stock from WIP (P2-M5). NULL until first return. */
    @Column(name = "returned_qty", precision = 19, scale = 6)
    private BigDecimal returnedQty;

    /** Qty scrapped during consumption (P2-M5). NULL until first scrap. */
    @Column(name = "scrap_qty", precision = 19, scale = 6)
    private BigDecimal scrapQty;

    /** Σ value debited to WIP for this leaf across all issue events. */
    @Column(name = "issued_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal issuedValue = BigDecimal.ZERO;

    /** Moving-average cost captured at last issue; NULL until first issue or if avg_cost was NULL. */
    @Column(name = "unit_cost_at_issue", precision = 19, scale = 4)
    private BigDecimal unitCostAtIssue;

    /** Unit-of-measure snapshot (P2-M5). Scalar FK → units_of_measure(id). */
    @Column(name = "unit_id")
    private Long unitId;

    /** true if a costed WIP leg was skipped for this leaf (avg_cost NULL — BR-MFG-06). */
    @Column(name = "cost_skipped", nullable = false)
    private boolean costSkipped = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 12)
    private ComponentLineStatus status = ComponentLineStatus.PLANNED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected WorkOrderComponent() { /* JPA */ }

    public WorkOrderComponent(Long workOrderId, Long companyId, Short lineNo,
                               Long componentProductId, String componentProductCode,
                               String componentProductName, BigDecimal plannedQty, Long actorId) {
        this.workOrderId           = workOrderId;
        this.companyId             = companyId;
        this.lineNo                = lineNo;
        this.componentProductId    = componentProductId;
        this.componentProductCode  = componentProductCode;
        this.componentProductName  = componentProductName;
        this.plannedQty            = plannedQty;
        this.createdAt             = Instant.now();
        this.createdBy             = actorId;
    }

    // -------------------------------------------------------------------------
    // Domain behaviour
    // -------------------------------------------------------------------------

    /** Record an issue: accumulate qty + value, update unit cost, update status. */
    public void recordIssue(BigDecimal qty, BigDecimal value, BigDecimal unitCost, Long actorId) {
        this.issuedQty       = this.issuedQty.add(qty);
        this.issuedValue     = this.issuedValue.add(value);
        this.unitCostAtIssue = unitCost;
        if (this.issuedQty.compareTo(this.plannedQty) >= 0) {
            this.status = ComponentLineStatus.ISSUED;
        } else {
            this.status = ComponentLineStatus.PARTIAL;
        }
        touch(actorId);
    }

    /** Mark cost-skipped for this leaf (avg_cost was NULL at issue — BR-MFG-06). */
    public void markCostSkipped(BigDecimal qty, Long actorId) {
        this.issuedQty   = this.issuedQty.add(qty);
        this.costSkipped = true;
        if (this.issuedQty.compareTo(this.plannedQty) >= 0) {
            this.status = ComponentLineStatus.ISSUED;
        } else {
            this.status = ComponentLineStatus.PARTIAL;
        }
        touch(actorId);
    }

    /** Reverse an issue (cancel path): subtract qty + value, reset to PLANNED if all reversed. */
    public void reverseIssue(BigDecimal qty, BigDecimal value, Long actorId) {
        this.issuedQty   = this.issuedQty.subtract(qty);
        this.issuedValue = this.issuedValue.subtract(value);
        if (this.issuedQty.compareTo(BigDecimal.ZERO) <= 0) {
            this.status      = ComponentLineStatus.PLANNED;
            this.issuedQty   = BigDecimal.ZERO;
            this.issuedValue = BigDecimal.ZERO;
        } else {
            this.status = ComponentLineStatus.PARTIAL;
        }
        touch(actorId);
    }

    /** Set the unit-of-measure snapshot (P2-M5). */
    public void setUnitId(Long unitId) {
        this.unitId = unitId;
    }

    /** Set the returned-to-stock quantity (P2-M5). */
    public void setReturnedQty(BigDecimal returnedQty) {
        this.returnedQty = returnedQty;
    }

    /** Set the scrapped quantity (P2-M5). */
    public void setScrapQty(BigDecimal scrapQty) {
        this.scrapQty = scrapQty;
    }

    private void touch(Long actorId) {
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------
    public Long                 getWorkOrderId()          { return workOrderId; }
    public Long                 getCompanyId()            { return companyId; }
    public Short                getLineNo()               { return lineNo; }
    public Long                 getComponentProductId()   { return componentProductId; }
    public String               getComponentProductCode() { return componentProductCode; }
    public String               getComponentProductName() { return componentProductName; }
    public BigDecimal           getPlannedQty()           { return plannedQty; }
    public BigDecimal           getIssuedQty()            { return issuedQty; }
    public BigDecimal           getReturnedQty()          { return returnedQty; }
    public BigDecimal           getScrapQty()             { return scrapQty; }
    public BigDecimal           getIssuedValue()          { return issuedValue; }
    public BigDecimal           getUnitCostAtIssue()      { return unitCostAtIssue; }
    public Long                 getUnitId()               { return unitId; }
    public boolean              isCostSkipped()           { return costSkipped; }
    public ComponentLineStatus  getStatus()               { return status; }
    public Instant              getCreatedAt()            { return createdAt; }
    public Long                 getCreatedBy()            { return createdBy; }
    public Instant              getUpdatedAt()            { return updatedAt; }
    public Long                 getUpdatedBy()            { return updatedBy; }
}
