package com.erp.modules.notifications.domain.entity;

import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Notification type catalogue entry — one row per (company, type_key) (ADR-0024 D-2a).
 * Seeded for every company via V25; new companies receive the seed via the bootstrap path.
 */
@Entity
@Table(name = "notification_types")
public class NotificationType extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "type_key", nullable = false, updatable = false, length = 40)
    private String typeKey;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "audience_permission", nullable = false, length = 60)
    private String audiencePermission;

    @Column(name = "branch_scoped", nullable = false)
    private boolean branchScoped = false;

    @Column(name = "default_channels", nullable = false, length = 60)
    private String defaultChannels;

    @Column(name = "severity", nullable = false, length = 10)
    private String severity;

    @Column(name = "title_template", nullable = false, length = 200)
    private String titleTemplate;

    @Column(name = "body_template", nullable = false, length = 1000)
    private String bodyTemplate;

    @Column(name = "link_route_template", nullable = false, length = 200)
    private String linkRouteTemplate;

    @Column(name = "attachment_ref", length = 60)
    private String attachmentRef;

    @Column(name = "company_enabled", nullable = false)
    private boolean companyEnabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MasterStatus status = MasterStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    public NotificationType() { }

    public Long       getCompanyId()          { return companyId; }
    public String     getTypeKey()            { return typeKey; }
    public String     getDisplayName()        { return displayName; }
    public String     getDescription()        { return description; }
    public String     getAudiencePermission() { return audiencePermission; }
    public boolean    isBranchScoped()        { return branchScoped; }
    public String     getDefaultChannels()    { return defaultChannels; }
    public String     getSeverity()           { return severity; }
    public String     getTitleTemplate()      { return titleTemplate; }
    public String     getBodyTemplate()       { return bodyTemplate; }
    public String     getLinkRouteTemplate()  { return linkRouteTemplate; }
    public String     getAttachmentRef()      { return attachmentRef; }
    public boolean    isCompanyEnabled()      { return companyEnabled; }
    public MasterStatus getStatus()           { return status; }
    public Instant    getCreatedAt()          { return createdAt; }
    public Long       getCreatedBy()          { return createdBy; }
    public Instant    getUpdatedAt()          { return updatedAt; }
    public Long       getUpdatedBy()          { return updatedBy; }

    // --- mutators for seeder/test use ---
    public void setCompanyId(Long companyId)               { this.companyId = companyId; }
    public void setTypeKey(String typeKey)                 { this.typeKey = typeKey; }
    public void setDisplayName(String displayName)         { this.displayName = displayName; }
    public void setDescription(String description)         { this.description = description; }
    public void setAudiencePermission(String p)            { this.audiencePermission = p; }
    public void setBranchScoped(boolean branchScoped)      { this.branchScoped = branchScoped; }
    public void setDefaultChannels(String defaultChannels) { this.defaultChannels = defaultChannels; }
    public void setSeverity(String severity)               { this.severity = severity; }
    public void setTitleTemplate(String titleTemplate)     { this.titleTemplate = titleTemplate; }
    public void setBodyTemplate(String bodyTemplate)       { this.bodyTemplate = bodyTemplate; }
    public void setLinkRouteTemplate(String t)             { this.linkRouteTemplate = t; }
    public void setAttachmentRef(String attachmentRef)     { this.attachmentRef = attachmentRef; }
    public void setCompanyEnabled(boolean companyEnabled)  { this.companyEnabled = companyEnabled; }
    public void setStatus(MasterStatus status)             { this.status = status; }
    public void setCreatedAt(Instant createdAt)            { this.createdAt = createdAt; }
    public void setCreatedBy(Long createdBy)               { this.createdBy = createdBy; }
    public void setUpdatedAt(Instant updatedAt)            { this.updatedAt = updatedAt; }
    public void setUpdatedBy(Long updatedBy)               { this.updatedBy = updatedBy; }

    public void touch(Instant at, Long actorId) {
        this.updatedAt = at;
        this.updatedBy = actorId;
    }

    @jakarta.persistence.PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
