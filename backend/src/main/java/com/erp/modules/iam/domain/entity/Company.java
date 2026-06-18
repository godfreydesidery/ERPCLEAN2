package com.erp.modules.iam.domain.entity;

import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A legal entity within the organisation (DATA-MODEL §1.2). Scoping parent of company-bound master
 * data; {@code code} is unique within the organisation.
 */
@Getter
@Entity
@Table(name = "companies")
public class Company extends UidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    @Column(name = "code", nullable = false, length = 20)
    @Setter
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    @Setter
    private String name;

    @Column(name = "legal_name", length = 200)
    @Setter
    private String legalName;

    @Column(name = "tax_id", length = 60)
    @Setter
    private String taxId;

    /** P2: VAT registration number (distinct from taxId). */
    @Column(name = "vrn", length = 40)
    @Setter
    private String vrn;

    /** P2: company-level branding logo reference. */
    @Column(name = "logo_ref", length = 255)
    @Setter
    private String logoRef;

    /** P2: doc-default fiscal-year start month (1-12). */
    @Column(name = "fiscal_year_start_month")
    @Setter
    private Short fiscalYearStartMonth;

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

    @Column(name = "time_zone", nullable = false, length = 64)
    @Setter
    private String timeZone = "Africa/Dar_es_Salaam";

    /** Company base currency (ADR-0005 D-4, ADR-0013 D-9). Added by V10. Default TZS. */
    @Column(name = "base_currency", nullable = false, length = 3)
    @Setter
    private String baseCurrency = "TZS";

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

    protected Company() {
        // JPA
    }

    public Company(Organisation organisation, String code, String name) {
        this.organisation = organisation;
        this.code = code;
        this.name = name;
    }
}
