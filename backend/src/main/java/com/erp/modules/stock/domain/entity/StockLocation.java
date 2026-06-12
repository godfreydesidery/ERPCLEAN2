package com.erp.modules.stock.domain.entity;

import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A named physical stock location within a branch (ADR-0028 D-4, FR-INVD-01..04).
 *
 * <p>Exactly one location per branch is the default ({@code is_default = true}).
 * Enforced at DB level by a partial-unique index {@code uq_stock_location_one_default}
 * and at service level by {@link com.erp.modules.stock.service.StockLocationServiceImpl}.
 *
 * <p>Extends {@link UidEntity} — carries {@code @Version} via the superclass.
 * No Lombok on entities (PROJECT-CONVENTIONS).
 */
@Entity
@Table(name = "stock_locations")
public class StockLocation extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false, length = 20)
    private LocationType locationType;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MasterStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected StockLocation() {
        // JPA
    }

    public StockLocation(Long companyId, Long branchId, String code, String name,
                         LocationType locationType, boolean isDefault, Long createdBy) {
        this.companyId    = companyId;
        this.branchId     = branchId;
        this.code         = code;
        this.name         = name;
        this.locationType = locationType;
        this.isDefault    = isDefault;
        this.status       = MasterStatus.ACTIVE;
        this.createdBy    = createdBy;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Domain behaviour
    // -------------------------------------------------------------------------

    /** Update mutable fields (name, type). */
    public void update(String name, LocationType locationType, Long actorId) {
        this.name         = name;
        this.locationType = locationType;
        this.updatedAt    = Instant.now();
        this.updatedBy    = actorId;
    }

    /** Mark as the branch default. Caller must clear any prior default in the same TX. */
    public void markDefault(Long actorId) {
        this.isDefault = true;
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }

    /** Clear the default flag (when setting a different location as default). */
    public void clearDefault(Long actorId) {
        this.isDefault = false;
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }

    /** Deactivate (soft-delete). Caller must ensure it is not the sole/default location. */
    public void deactivate(Long actorId) {
        this.status    = MasterStatus.INACTIVE;
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }

    /** Reactivate a previously deactivated location. */
    public void reactivate(Long actorId) {
        this.status    = MasterStatus.ACTIVE;
        this.updatedAt = Instant.now();
        this.updatedBy = actorId;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Long         getCompanyId()   { return companyId; }
    public Long         getBranchId()    { return branchId; }
    public String       getCode()        { return code; }
    public String       getName()        { return name; }
    public LocationType getLocationType(){ return locationType; }
    public boolean      isDefault()      { return isDefault; }
    public MasterStatus getStatus()      { return status; }
    public Instant      getCreatedAt()   { return createdAt; }
    public Long         getCreatedBy()   { return createdBy; }
    public Instant      getUpdatedAt()   { return updatedAt; }
    public Long         getUpdatedBy()   { return updatedBy; }
}
