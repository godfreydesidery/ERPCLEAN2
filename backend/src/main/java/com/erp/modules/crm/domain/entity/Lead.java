package com.erp.modules.crm.domain.entity;

import com.erp.modules.crm.domain.enums.LeadSource;
import com.erp.modules.crm.domain.enums.LeadStatus;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * CRM lead — an unqualified prospect with its own contact data (ADR-0031 D-3).
 * Not a Parties customer until qualified (BR-CRM-01/09).
 * Lifecycle: NEW → CONTACTED → QUALIFIED → CONVERTED | DISQUALIFIED.
 */
@Getter
@Entity
@Table(name = "leads")
public class Lead extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "lead_number", nullable = false, length = 30)
    @Setter
    private String leadNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "lead_status", nullable = false, length = 20)
    @Setter
    private LeadStatus leadStatus = LeadStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(name = "lead_source", nullable = false, length = 30)
    @Setter
    private LeadSource leadSource;

    @Column(name = "display_name", nullable = false, length = 200)
    @Setter
    private String displayName;

    @Column(name = "company_name", length = 200)
    @Setter
    private String companyName;

    @Column(name = "contact_person", length = 200)
    @Setter
    private String contactPerson;

    @Column(name = "phone", length = 40)
    @Setter
    private String phone;

    @Column(name = "email", length = 160)
    @Setter
    private String email;

    @Column(name = "owner_user_id")
    @Setter
    private Long ownerUserId;

    /** Set on qualify — scalar FK to customers(id). NULL while NEW/CONTACTED. */
    @Column(name = "customer_id")
    @Setter
    private Long customerId;

    /** Scalar uid mirror of customer_id (for DTO reads without a join). */
    @Column(name = "customer_uid", length = 26)
    @Setter
    private String customerUid;

    /** P2: lead-qualification estimate (rating/score remains ADR-deferred). */
    @Column(name = "estimated_value", precision = 19, scale = 4)
    @Setter
    private BigDecimal estimatedValue;

    @Column(name = "next_follow_up_date")
    @Setter
    private LocalDate nextFollowUpDate;

    @Column(name = "industry", length = 120)
    @Setter
    private String industry;

    @Column(name = "region", length = 120)
    @Setter
    private String region;

    @Column(name = "disqualify_reason", length = 255)
    @Setter
    private String disqualifyReason;

    @Column(name = "notes", length = 1000)
    @Setter
    private String notes;

    @Column(name = "qualified_at")
    @Setter
    private Instant qualifiedAt;

    @Column(name = "converted_at")
    @Setter
    private Instant convertedAt;

    @Column(name = "disqualified_at")
    @Setter
    private Instant disqualifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    protected Lead() { /* JPA */ }

    public Lead(Long companyId, Long branchId, String displayName, LeadSource leadSource,
                Long createdBy) {
        this.companyId = companyId;
        this.branchId = branchId;
        this.displayName = displayName;
        this.leadSource = leadSource;
        this.createdBy = createdBy;
    }
}
