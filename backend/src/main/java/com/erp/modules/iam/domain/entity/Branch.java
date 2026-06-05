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

/**
 * A physical location under a company (DATA-MODEL §1.3). Smallest unit at which stock and a
 * business day exist. {@code code} is unique within the company; at most one branch per company is
 * the default ({@code is_default}, enforced by a partial unique index).
 */
@Entity
@Table(name = "branch")
public class Branch extends UidEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 160)
    private String name;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone = "Africa/Dar_es_Salaam";

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MasterStatus status = MasterStatus.ACTIVE;

    protected Branch() {
        // JPA
    }

    public Branch(Company company, String code, String name) {
        this.company = company;
        this.code = code;
        this.name = name;
    }

    public Company getCompany() {
        return company;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    public MasterStatus getStatus() {
        return status;
    }

    public void setStatus(MasterStatus status) {
        this.status = status;
    }
}
