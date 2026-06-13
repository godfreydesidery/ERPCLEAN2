package com.erp.modules.notifications.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A single notification message addressed to one recipient on one channel (ADR-0024 D-2b).
 * Immutable except {@code isRead} / {@code readAt} (BR-NOTIF-05).
 * The unique constraint on (company_id, trigger_key, recipient_user_id, channel) is the
 * per-recipient idempotency backstop (BR-NOTIF-07, NFR-NOTIF-02).
 */
@Entity
@Table(name = "notifications")
public class Notification extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", updatable = false)
    private Long branchId;

    @Column(name = "notification_type_id", nullable = false, updatable = false)
    private Long notificationTypeId;

    @Column(name = "type_key", nullable = false, updatable = false, length = 40)
    private String typeKey;

    @Column(name = "recipient_user_id", nullable = false, updatable = false)
    private Long recipientUserId;

    @Column(name = "channel", nullable = false, updatable = false, length = 10)
    private String channel;

    @Column(name = "severity", nullable = false, updatable = false, length = 10)
    private String severity;

    @Column(name = "title", nullable = false, updatable = false, length = 200)
    private String title;

    @Column(name = "body", nullable = false, updatable = false, length = 1000)
    private String body;

    @Column(name = "source_aggregate_type", updatable = false, length = 40)
    private String sourceAggregateType;

    @Column(name = "source_uid", updatable = false, length = 26)
    private String sourceUid;

    @Column(name = "link_route", updatable = false, length = 200)
    private String linkRoute;

    @Column(name = "trigger_key", nullable = false, updatable = false, length = 80)
    private String triggerKey;

    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected Notification() { }

    public Notification(Long companyId, Long branchId, Long notificationTypeId,
                        String typeKey, Long recipientUserId, String channel,
                        String severity, String title, String body,
                        String sourceAggregateType, String sourceUid, String linkRoute,
                        String triggerKey, Long createdBy) {
        this.companyId            = companyId;
        this.branchId             = branchId;
        this.notificationTypeId   = notificationTypeId;
        this.typeKey              = typeKey;
        this.recipientUserId      = recipientUserId;
        this.channel              = channel;
        this.severity             = severity;
        this.title                = title;
        this.body                 = body;
        this.sourceAggregateType  = sourceAggregateType;
        this.sourceUid            = sourceUid;
        this.linkRoute            = linkRoute;
        this.triggerKey           = triggerKey;
        this.createdBy            = createdBy;
    }

    @jakarta.persistence.PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    // Read-state mutation (the ONLY mutable field, BR-NOTIF-05)
    public void markRead(Instant at) {
        this.isRead = true;
        this.readAt = at;
    }

    public Long    getCompanyId()           { return companyId; }
    public Long    getBranchId()            { return branchId; }
    public Long    getNotificationTypeId()  { return notificationTypeId; }
    public String  getTypeKey()             { return typeKey; }
    public Long    getRecipientUserId()     { return recipientUserId; }
    public String  getChannel()             { return channel; }
    public String  getSeverity()            { return severity; }
    public String  getTitle()               { return title; }
    public String  getBody()                { return body; }
    public String  getSourceAggregateType() { return sourceAggregateType; }
    public String  getSourceUid()           { return sourceUid; }
    public String  getLinkRoute()           { return linkRoute; }
    public String  getTriggerKey()          { return triggerKey; }
    public boolean isRead()                 { return isRead; }
    public Instant getReadAt()              { return readAt; }
    public Instant getCreatedAt()           { return createdAt; }
    public Long    getCreatedBy()           { return createdBy; }
}
