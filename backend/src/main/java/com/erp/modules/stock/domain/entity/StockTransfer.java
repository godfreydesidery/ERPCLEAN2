package com.erp.modules.stock.domain.entity;

import com.erp.modules.stock.domain.enums.StockTransferStatus;
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
 * Inter-location transfer header (ADR-0028 D-5, FR-INVD-08..11).
 *
 * <p>Value-preserving; posts no GL (D-2 single average → net-zero on 1300).
 * Lifecycle: DRAFT → DISPATCHED → RECEIVED (IN_TRANSIT) or DRAFT → COMPLETED (INSTANT).
 * Numbering: TRF-#### via {@link com.erp.modules.stock.service.WarehouseNumberGenerator}.
 *
 * <p>Extends {@link UidEntity} — carries {@code @Version} via the superclass.
 * No Lombok on entities (PROJECT-CONVENTIONS).
 */
@Entity
@Table(name = "stock_transfers")
public class StockTransfer extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "transfer_number", nullable = false, updatable = false, length = 30)
    private String transferNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StockTransferStatus status;

    @Column(name = "transfer_mode", nullable = false, updatable = false, length = 12)
    private String transferMode;

    @Column(name = "source_branch_id", nullable = false, updatable = false)
    private Long sourceBranchId;

    @Column(name = "source_location_id", nullable = false, updatable = false)
    private Long sourceLocationId;

    @Column(name = "dest_branch_id", nullable = false, updatable = false)
    private Long destBranchId;

    @Column(name = "dest_location_id", nullable = false, updatable = false)
    private Long destLocationId;

    @Column(name = "transfer_date", nullable = false)
    private LocalDate transferDate;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "received_at")
    private Instant receivedAt;

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

    protected StockTransfer() {
        // JPA
    }

    public StockTransfer(Long companyId, String transferNumber, String transferMode,
                         Long sourceBranchId, Long sourceLocationId,
                         Long destBranchId, Long destLocationId,
                         LocalDate transferDate, String notes, Long createdBy) {
        this.companyId        = companyId;
        this.transferNumber   = transferNumber;
        this.transferMode     = transferMode;
        this.status           = StockTransferStatus.DRAFT;
        this.sourceBranchId   = sourceBranchId;
        this.sourceLocationId = sourceLocationId;
        this.destBranchId     = destBranchId;
        this.destLocationId   = destLocationId;
        this.transferDate     = transferDate;
        this.notes            = notes;
        this.createdBy        = createdBy;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Domain behaviour (lifecycle transitions)
    // -------------------------------------------------------------------------

    public void dispatch(Long actorId) {
        this.status       = StockTransferStatus.DISPATCHED;
        this.dispatchedAt = Instant.now();
        this.updatedAt    = Instant.now();
        this.updatedBy    = actorId;
    }

    public void receive(Long actorId) {
        this.status     = StockTransferStatus.RECEIVED;
        this.receivedAt = Instant.now();
        this.updatedAt  = Instant.now();
        this.updatedBy  = actorId;
    }

    public void complete(Long actorId) {
        this.status    = StockTransferStatus.COMPLETED;
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }

    public void cancel(Long actorId) {
        this.status    = StockTransferStatus.CANCELLED;
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Long               getCompanyId()       { return companyId; }
    public String             getTransferNumber()  { return transferNumber; }
    public StockTransferStatus getStatus()          { return status; }
    public String             getTransferMode()    { return transferMode; }
    public Long               getSourceBranchId()  { return sourceBranchId; }
    public Long               getSourceLocationId(){ return sourceLocationId; }
    public Long               getDestBranchId()    { return destBranchId; }
    public Long               getDestLocationId()  { return destLocationId; }
    public LocalDate          getTransferDate()    { return transferDate; }
    public Instant            getDispatchedAt()    { return dispatchedAt; }
    public Instant            getReceivedAt()      { return receivedAt; }
    public String             getNotes()           { return notes; }
    public Instant            getCreatedAt()       { return createdAt; }
    public Long               getCreatedBy()       { return createdBy; }
    public Instant            getUpdatedAt()       { return updatedAt; }
    public Long               getUpdatedBy()       { return updatedBy; }
}
