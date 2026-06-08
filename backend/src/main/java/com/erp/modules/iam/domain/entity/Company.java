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

    protected Company() {
        // JPA
    }

    public Company(Organisation organisation, String code, String name) {
        this.organisation = organisation;
        this.code = code;
        this.name = name;
    }
}
