package com.erp.modules.parties.domain.entity;

import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Shared columns for all four party master tables (ADR-0006 D-2).
 *
 * <p>Extends {@link UidEntity} (id + uid + version). Each concrete subclass adds only its own
 * kind-specific columns. All shared fields are settable — party profiles are updatable.
 */
@Getter
@MappedSuperclass
public abstract class PartyBase extends UidEntity {

    /** FK to {@code companies.id} — tenant scope. Never updated (BR-PARTY-02). */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** System-assigned code, e.g. {@code CUST-0001} (ADR-0006 D-7). Never user-editable. */
    @Column(name = "code", nullable = false, updatable = false, length = 20)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "party_type", nullable = false, length = 20)
    @Setter
    private PartyType partyType;

    @Column(name = "display_name", nullable = false, length = 200)
    @Setter
    private String displayName;

    @Column(name = "legal_name", length = 200)
    @Setter
    private String legalName;

    @Column(name = "tin", length = 20)
    @Setter
    private String tin;

    @Column(name = "vat_registered", nullable = false)
    @Setter
    private boolean vatRegistered = false;

    @Column(name = "vrn", length = 20)
    @Setter
    private String vrn;

    @Column(name = "business_reg_no", length = 40)
    @Setter
    private String businessRegNo;

    @Column(name = "mobile_money_no", length = 30)
    @Setter
    private String mobileMoneyNo;

    @Column(name = "phone", length = 40)
    @Setter
    private String phone;

    @Column(name = "email", length = 160)
    @Setter
    private String email;

    @Column(name = "physical_address", length = 255)
    @Setter
    private String physicalAddress;

    @Column(name = "postal_address", length = 255)
    @Setter
    private String postalAddress;

    @Column(name = "region", length = 80)
    @Setter
    private String region;

    @Column(name = "district", length = 80)
    @Setter
    private String district;

    /** P2: ISO-3166 alpha-2 country code (nullable). Shared across all four party master tables. */
    @Column(name = "country", length = 2)
    @Setter
    private String country;

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

    protected PartyBase() {
        // JPA
    }

    protected PartyBase(Long companyId, String code, PartyType partyType, String displayName,
                        Long createdBy) {
        this.companyId = companyId;
        this.code = code;
        this.partyType = partyType;
        this.displayName = displayName;
        this.createdBy = createdBy;
    }
}
