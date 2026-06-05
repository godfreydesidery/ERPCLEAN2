package com.erp.modules.iam.domain.entity;

import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * The top of the multi-company tree — one per deployment (DATA-MODEL §1.1). Parent of all
 * companies. No tenant columns (it IS the tenant root). No Lombok on entities (conventions §3.7).
 */
@Entity
@Table(name = "organisation")
public class Organisation extends UidEntity {

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "legal_name", length = 200)
    private String legalName;

    @Column(name = "default_time_zone", nullable = false, length = 64)
    private String defaultTimeZone = "Africa/Dar_es_Salaam";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MasterStatus status = MasterStatus.ACTIVE;

    protected Organisation() {
        // JPA
    }

    public Organisation(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLegalName() {
        return legalName;
    }

    public void setLegalName(String legalName) {
        this.legalName = legalName;
    }

    public String getDefaultTimeZone() {
        return defaultTimeZone;
    }

    public void setDefaultTimeZone(String defaultTimeZone) {
        this.defaultTimeZone = defaultTimeZone;
    }

    public MasterStatus getStatus() {
        return status;
    }

    public void setStatus(MasterStatus status) {
        this.status = status;
    }
}
