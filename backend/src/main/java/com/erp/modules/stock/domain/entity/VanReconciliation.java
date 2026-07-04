package com.erp.modules.stock.domain.entity;

import com.erp.modules.stock.domain.enums.VanReconciliationStatus;
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
 * Van-stock reconciliation worksheet header (ADR-0051 D-8).
 *
 * <p>A day-end record for a VAN {@link StockLocation}: {@code loaded}/{@code returned} are derived
 * from the transfer ledger, {@code sold}/{@code physical} are entered by the agent. This is a
 * <strong>record-only</strong> worksheet — {@link #reconcile(Long)} never posts a stock movement or
 * GL entry (the van's on-hand is not truthful; see ADR-0051 Context §3). Lifecycle:
 * {@code OPEN} → {@code RECONCILED} or {@code OPEN} → {@code CANCELLED}.
 *
 * <p>Extends {@link UidEntity} — carries {@code @Version} via the superclass.
 * No Lombok on entities (PROJECT-CONVENTIONS).
 */
@Entity
@Table(name = "van_reconciliations")
public class VanReconciliation extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "recon_number", nullable = false, updatable = false, length = 30)
    private String reconNumber;

    @Column(name = "van_location_id", nullable = false, updatable = false)
    private Long vanLocationId;

    /** agents.id — the route agent who runs this van. Nullable (a van may have no agent assigned). */
    @Column(name = "agent_id", updatable = false)
    private Long agentId;

    @Column(name = "business_date", nullable = false, updatable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private VanReconciliationStatus status;

    @Column(name = "notes", length = 500)
    private String notes;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "reconciled_by")
    private Long reconciledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected VanReconciliation() {
        // JPA
    }

    public VanReconciliation(Long companyId, Long branchId, String reconNumber, Long vanLocationId,
                             Long agentId, LocalDate businessDate, String notes, Long createdBy) {
        this.companyId     = companyId;
        this.branchId      = branchId;
        this.reconNumber   = reconNumber;
        this.vanLocationId = vanLocationId;
        this.agentId       = agentId;
        this.businessDate  = businessDate;
        this.status        = VanReconciliationStatus.OPEN;
        this.notes         = notes;
        this.createdBy     = createdBy;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Domain behaviour (lifecycle)
    // -------------------------------------------------------------------------

    /**
     * Mark this worksheet RECONCILED. RECORD-ONLY (ADR-0051 D-8.2): no stock movement, no GL
     * journal is posted here — the caller publishes an informational {@code VAN.RECONCILED} outbox
     * event separately.
     */
    public void reconcile(Long actorId) {
        this.status       = VanReconciliationStatus.RECONCILED;
        this.reconciledAt = Instant.now();
        this.reconciledBy = actorId;
        this.updatedAt    = Instant.now();
        this.updatedBy    = actorId;
    }

    public void cancel(Long actorId) {
        this.status      = VanReconciliationStatus.CANCELLED;
        this.cancelledAt = Instant.now();
        this.cancelledBy = actorId;
        this.updatedAt   = Instant.now();
        this.updatedBy   = actorId;
    }

    /**
     * Bump {@code updated_at}/{@code updated_by} without changing {@code status} — used by
     * {@code enterLines} (backend review fix) so that saving line rows also dirties the header and
     * participates in optimistic locking (via the inherited {@code @Version}). A concurrent
     * {@link #reconcile}/{@link #cancel} on the same header then surfaces as an
     * {@code ObjectOptimisticLockingFailureException} instead of silently mutating a worksheet that
     * has just been frozen.
     */
    public void touch(Long actorId) {
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Long                     getCompanyId()     { return companyId; }
    public Long                     getBranchId()       { return branchId; }
    public String                   getReconNumber()    { return reconNumber; }
    public Long                     getVanLocationId()  { return vanLocationId; }
    public Long                     getAgentId()        { return agentId; }
    public LocalDate                getBusinessDate()   { return businessDate; }
    public VanReconciliationStatus  getStatus()         { return status; }
    public String                   getNotes()          { return notes; }
    public Instant                  getReconciledAt()   { return reconciledAt; }
    public Long                     getReconciledBy()   { return reconciledBy; }
    public Instant                  getCancelledAt()    { return cancelledAt; }
    public Long                     getCancelledBy()    { return cancelledBy; }
    public Instant                  getCreatedAt()      { return createdAt; }
    public Long                     getCreatedBy()      { return createdBy; }
    public Instant                  getUpdatedAt()      { return updatedAt; }
    public Long                     getUpdatedBy()      { return updatedBy; }
}
