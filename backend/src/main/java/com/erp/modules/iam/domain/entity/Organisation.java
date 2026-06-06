package com.erp.modules.iam.domain.entity;

import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

    protected Organisation() {
        // JPA
    }

    public Organisation(String name) {
        this.name = name;
    }
}
