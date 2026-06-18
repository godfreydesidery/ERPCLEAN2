package com.erp.modules.stock.domain.entity;

import com.erp.modules.stock.domain.enums.StockCountStatus;
import com.erp.modules.stock.domain.enums.StockCountType;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Stock count / physical inventory header (ADR-0028 D-6, FR-INVD-12..17).
 *
 * <p>Lifecycle: DRAFT → COUNTING → POSTED (or CANCELLED).
 * POSTED is immutable; corrections require a new count (FR-INVD-15).
 * Numbering: CNT-#### via {@link com.erp.modules.stock.service.WarehouseNumberGenerator}.
 *
 * <p>Extends {@link UidEntity} — carries {@code @Version} via the superclass.
 * No Lombok on entities (PROJECT-CONVENTIONS).
 */
@Entity
@Table(name = "stock_counts")
public class StockCount extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "count_number", nullable = false, updatable = false, length = 30)
    private String countNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StockCountStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "count_type", nullable = false, length = 12)
    private StockCountType countType;

    @Column(name = "location_id", nullable = false, updatable = false)
    private Long locationId;

    @Column(name = "count_date", nullable = false)
    private LocalDate countDate;

    @Column(name = "frozen_at")
    private Instant frozenAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** The net-variance journal uid posted on the post action (diagnostic). */
    @Column(name = "variance_gl_entry_uid", length = 26)
    private String varianceGlEntryUid;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected StockCount() {
        // JPA
    }

    public StockCount(Long companyId, Long branchId, String countNumber, String countType,
                      Long locationId, LocalDate countDate, String notes, Long createdBy) {
        this.companyId   = companyId;
        this.branchId    = branchId;
        this.countNumber = countNumber;
        this.countType   = countType != null ? StockCountType.valueOf(countType) : null;
        this.status      = StockCountStatus.DRAFT;
        this.locationId  = locationId;
        this.countDate   = countDate;
        this.notes       = notes;
        this.createdBy   = createdBy;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Domain behaviour (lifecycle)
    // -------------------------------------------------------------------------

    /** Move to COUNTING and record the freeze timestamp. */
    public void freeze(Long actorId) {
        this.status    = StockCountStatus.COUNTING;
        this.frozenAt  = Instant.now();
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }

    /** Record the net-variance GL journal uid and mark as POSTED. */
    public void markPosted(String glEntryUid, Long actorId) {
        this.status              = StockCountStatus.POSTED;
        this.varianceGlEntryUid  = glEntryUid;
        this.postedAt            = Instant.now();
        this.updatedAt           = Instant.now();
        this.updatedBy           = actorId;
    }

    public void cancel(Long actorId) {
        this.status      = StockCountStatus.CANCELLED;
        this.cancelledAt = Instant.now();
        this.updatedAt   = Instant.now();
        this.updatedBy   = actorId;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Long             getCompanyId()          { return companyId; }
    public Long             getBranchId()           { return branchId; }
    public String           getCountNumber()        { return countNumber; }
    public StockCountStatus getStatus()             { return status; }
    public StockCountType   getCountType()          { return countType; }
    public Long             getLocationId()         { return locationId; }
    public LocalDate        getCountDate()          { return countDate; }
    public Instant          getFrozenAt()           { return frozenAt; }
    public Instant          getPostedAt()           { return postedAt; }
    public Instant          getCancelledAt()        { return cancelledAt; }
    public String           getVarianceGlEntryUid() { return varianceGlEntryUid; }
    public String           getNotes()              { return notes; }
    public Instant          getCreatedAt()          { return createdAt; }
    public Long             getCreatedBy()          { return createdBy; }
    public Instant          getUpdatedAt()          { return updatedAt; }
    public Long             getUpdatedBy()          { return updatedBy; }
}
