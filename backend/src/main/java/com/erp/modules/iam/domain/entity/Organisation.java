package com.erp.modules.iam.domain.entity;

import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * The top of the multi-company tree — one per deployment (DATA-MODEL §1.1). Parent of all
 * companies. No tenant columns (it IS the tenant root).
 */
@Getter
@Entity
@Table(name = "organisations")
public class Organisation extends UidEntity {

    @Column(name = "name", nullable = false, length = 160)
    @Setter
    private String name;

    @Column(name = "legal_name", length = 200)
    @Setter
    private String legalName;

    @Column(name = "default_time_zone", nullable = false, length = 64)
    @Setter
    private String defaultTimeZone = "Africa/Dar_es_Salaam";

    // P2 D7 — contact + address block
    @Column(name = "contact_phone", length = 40)
    @Setter
    private String contactPhone;

    @Column(name = "contact_email", length = 160)
    @Setter
    private String contactEmail;

    @Column(name = "address_line1", length = 160)
    @Setter
    private String addressLine1;

    @Column(name = "address_line2", length = 160)
    @Setter
    private String addressLine2;

    @Column(name = "city", length = 80)
    @Setter
    private String city;

    @Column(name = "region", length = 80)
    @Setter
    private String region;

    @Column(name = "country", length = 80)
    @Setter
    private String country;

    // P2 D7 — subscription/plan tracking (descriptive in v1)
    @Column(name = "subscription_plan", length = 40)
    @Setter
    private String subscriptionPlan;

    @Column(name = "subscription_status", length = 20)
    @Setter
    private String subscriptionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    protected Organisation() {
        // JPA
    }

    public Organisation(String name) {
        this.name = name;
    }
}
