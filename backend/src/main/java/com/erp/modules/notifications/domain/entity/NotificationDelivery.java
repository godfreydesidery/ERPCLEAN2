package com.erp.modules.notifications.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Append-only delivery-log row — one per dispatch attempt (ADR-0024 D-2d).
 * The email path inserts PENDING then transitions to SENT/FAILED on the same row.
 * A retry is a new row with attempt_no+1. Never edited by users (BR-NOTIF-10).
 */
@Entity
@Table(name = "notification_deliveries")
public class NotificationDelivery extends UidEntity {

    @Column(name = "notification_id")
    private Long notificationId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "type_key", nullable = false, updatable = false, length = 40)
    private String typeKey;

    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Column(name = "channel", nullable = false, updatable = false, length = 10)
    private String channel;

    @Column(name = "outcome", nullable = false, length = 12)
    private String outcome;

    @Column(name = "suppression_reason", length = 20)
    private String suppressionReason;

    @Column(name = "attempt_no", nullable = false)
    private short attemptNo = 1;

    @Column(name = "error", length = 1000)
    private String error;

    @Column(name = "trigger_key", nullable = false, updatable = false, length = 80)
    private String triggerKey;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected NotificationDelivery() { }

    /** Factory for a SENT in-app delivery (immediate). */
    public static NotificationDelivery sent(Long notificationId, Long companyId,
                                             String typeKey, Long recipientUserId,
                                             String channel, String triggerKey) {
        NotificationDelivery d = new NotificationDelivery();
        d.notificationId  = notificationId;
        d.companyId       = companyId;
        d.typeKey         = typeKey;
        d.recipientUserId = recipientUserId;
        d.channel         = channel;
        d.outcome         = "SENT";
        d.triggerKey      = triggerKey;
        d.attemptedAt     = Instant.now();
        d.completedAt     = Instant.now();
        return d;
    }

    /** Factory for a SUPPRESSED delivery with a reason. */
    public static NotificationDelivery suppressed(Long companyId, String typeKey,
                                                   Long recipientUserId, String channel,
                                                   String suppressionReason, String triggerKey) {
        NotificationDelivery d = new NotificationDelivery();
        d.companyId        = companyId;
        d.typeKey          = typeKey;
        d.recipientUserId  = recipientUserId;
        d.channel          = channel;
        d.outcome          = "SUPPRESSED";
        d.suppressionReason = suppressionReason;
        d.triggerKey       = triggerKey;
        d.attemptedAt      = Instant.now();
        return d;
    }

    /** Factory for a PENDING email delivery. */
    public static NotificationDelivery pending(Long notificationId, Long companyId,
                                                String typeKey, Long recipientUserId,
                                                String triggerKey) {
        NotificationDelivery d = new NotificationDelivery();
        d.notificationId  = notificationId;
        d.companyId       = companyId;
        d.typeKey         = typeKey;
        d.recipientUserId = recipientUserId;
        d.channel         = "EMAIL";
        d.outcome         = "PENDING";
        d.triggerKey      = triggerKey;
        d.attemptedAt     = Instant.now();
        return d;
    }

    @jakarta.persistence.PrePersist
    void prePersist() {
        if (attemptedAt == null) attemptedAt = Instant.now();
    }

    /** Controlled state machine: PENDING → SENT/FAILED (BR-NOTIF-10). */
    public void complete(boolean success, String errorMsg) {
        this.outcome     = success ? "SENT" : "FAILED";
        this.error       = errorMsg != null && errorMsg.length() > 1000
                           ? errorMsg.substring(0, 1000) : errorMsg;
        this.completedAt = Instant.now();
    }

    public Long    getNotificationId()    { return notificationId; }
    public Long    getCompanyId()         { return companyId; }
    public String  getTypeKey()           { return typeKey; }
    public Long    getRecipientUserId()   { return recipientUserId; }
    public String  getChannel()           { return channel; }
    public String  getOutcome()           { return outcome; }
    public String  getSuppressionReason() { return suppressionReason; }
    public short   getAttemptNo()         { return attemptNo; }
    public String  getError()             { return error; }
    public String  getTriggerKey()        { return triggerKey; }
    public Instant getAttemptedAt()       { return attemptedAt; }
    public Instant getCompletedAt()       { return completedAt; }
}
