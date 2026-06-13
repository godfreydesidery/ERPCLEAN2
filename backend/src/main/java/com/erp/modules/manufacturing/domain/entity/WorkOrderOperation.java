package com.erp.modules.manufacturing.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Optional routing operation on a work order (ADR-0035 D-3).
 *
 * <p>Descriptive + cost-input vehicle in v1: each operation carries optional labour/overhead amounts
 * that feed the WIP accumulator when applied via {@code WorkOrderCostingService.applyCost}.
 * No Lombok on entities (PROJECT-CONVENTIONS).
 */
@Entity
@Table(name = "work_order_operations")
public class WorkOrderOperation extends UidEntity {

    @Column(name = "work_order_id", nullable = false, updatable = false)
    private Long workOrderId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "seq_no", nullable = false)
    private Short seqNo;

    @Column(name = "description", nullable = false, length = 200)
    private String description;

    @Column(name = "work_centre", length = 80)
    private String workCentre;

    @Column(name = "labour_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal labourAmount = BigDecimal.ZERO;

    @Column(name = "overhead_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal overheadAmount = BigDecimal.ZERO;

    /** true once this operation's labour/overhead has been posted to WIP (idempotency guard). */
    @Column(name = "applied", nullable = false)
    private boolean applied = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected WorkOrderOperation() { /* JPA */ }

    public WorkOrderOperation(Long workOrderId, Long companyId, Short seqNo,
                               String description, String workCentre,
                               BigDecimal labourAmount, BigDecimal overheadAmount, Long actorId) {
        this.workOrderId    = workOrderId;
        this.companyId      = companyId;
        this.seqNo          = seqNo;
        this.description    = description;
        this.workCentre     = workCentre;
        this.labourAmount   = labourAmount != null ? labourAmount : BigDecimal.ZERO;
        this.overheadAmount = overheadAmount != null ? overheadAmount : BigDecimal.ZERO;
        this.createdAt      = Instant.now();
        this.createdBy      = actorId;
    }

    // -------------------------------------------------------------------------
    // Domain behaviour
    // -------------------------------------------------------------------------

    public void markApplied(Long actorId) {
        this.applied   = true;
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }

    public void unmarkApplied(Long actorId) {
        this.applied   = false;
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------
    public Long        getWorkOrderId()    { return workOrderId; }
    public Long        getCompanyId()      { return companyId; }
    public Short       getSeqNo()          { return seqNo; }
    public String      getDescription()    { return description; }
    public String      getWorkCentre()     { return workCentre; }
    public BigDecimal  getLabourAmount()   { return labourAmount; }
    public BigDecimal  getOverheadAmount() { return overheadAmount; }
    public boolean     isApplied()         { return applied; }
    public Instant     getCreatedAt()      { return createdAt; }
    public Long        getCreatedBy()      { return createdBy; }
    public Instant     getUpdatedAt()      { return updatedAt; }
    public Long        getUpdatedBy()      { return updatedBy; }
}
