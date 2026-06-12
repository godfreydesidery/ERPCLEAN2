package com.erp.modules.notifications.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Scanner dedup state — one row per (company, type_key, condition_key) (ADR-0024 D-2e).
 * Prevents the scanner from re-notifying an already-notified condition (BR-NOTIF-08).
 * The {@code armed} flag enables low-stock re-arm on recovery.
 */
@Entity
@Table(name = "notification_scan_markers")
public class NotificationScanMarker extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "type_key", nullable = false, updatable = false, length = 40)
    private String typeKey;

    @Column(name = "condition_key", nullable = false, updatable = false, length = 120)
    private String conditionKey;

    @Column(name = "last_notified_at", nullable = false)
    private Instant lastNotifiedAt;

    /**
     * Re-arm flag for low-stock: false = notified and still low (skip), true = recovered (re-fire).
     * For invoice-overdue this stays false (once overdue = once notified).
     */
    @Column(name = "armed", nullable = false)
    private boolean armed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected NotificationScanMarker() { }

    public NotificationScanMarker(Long companyId, String typeKey, String conditionKey) {
        this.companyId    = companyId;
        this.typeKey      = typeKey;
        this.conditionKey = conditionKey;
    }

    @jakarta.persistence.PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        if (lastNotifiedAt == null) lastNotifiedAt = Instant.now();
    }

    public void recordNotified(Instant at) {
        this.lastNotifiedAt = at;
        this.armed          = false;
        this.updatedAt      = at;
    }

    public void reArm(Instant at) {
        this.armed     = true;
        this.updatedAt = at;
    }

    public Long    getCompanyId()      { return companyId; }
    public String  getTypeKey()        { return typeKey; }
    public String  getConditionKey()   { return conditionKey; }
    public Instant getLastNotifiedAt() { return lastNotifiedAt; }
    public boolean isArmed()           { return armed; }
    public Instant getCreatedAt()      { return createdAt; }
    public Long    getCreatedBy()      { return createdBy; }
    public Instant getUpdatedAt()      { return updatedAt; }
    public Long    getUpdatedBy()      { return updatedBy; }
}
