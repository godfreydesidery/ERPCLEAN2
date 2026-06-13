package com.erp.modules.notifications.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Per-user, per-company, per-type preference row (ADR-0024 D-2c).
 * Absent a row means type defaults apply (BR-NOTIF-06).
 * Created lazily on first set via upsert.
 */
@Entity
@Table(name = "notification_preferences")
public class NotificationPreference extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "type_key", nullable = false, updatable = false, length = 40)
    private String typeKey;

    @Column(name = "muted", nullable = false)
    private boolean muted = false;

    @Column(name = "channels_enabled", length = 60)
    private String channelsEnabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected NotificationPreference() { }

    public NotificationPreference(Long companyId, Long userId, String typeKey,
                                   boolean muted, String channelsEnabled, Long actorId) {
        this.companyId       = companyId;
        this.userId          = userId;
        this.typeKey         = typeKey;
        this.muted           = muted;
        this.channelsEnabled = channelsEnabled;
        this.createdBy       = actorId;
    }

    @jakarta.persistence.PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public void update(boolean muted, String channelsEnabled, Instant at, Long actorId) {
        this.muted           = muted;
        this.channelsEnabled = channelsEnabled;
        this.updatedAt       = at;
        this.updatedBy       = actorId;
    }

    public Long    getCompanyId()       { return companyId; }
    public Long    getUserId()          { return userId; }
    public String  getTypeKey()         { return typeKey; }
    public boolean isMuted()            { return muted; }
    public String  getChannelsEnabled() { return channelsEnabled; }
    public Instant getCreatedAt()       { return createdAt; }
    public Long    getCreatedBy()       { return createdBy; }
    public Instant getUpdatedAt()       { return updatedAt; }
    public Long    getUpdatedBy()       { return updatedBy; }
}
