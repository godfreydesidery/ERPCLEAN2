package com.erp.modules.hr.domain.entity;

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

/** Minimal company-scoped, FLAT job-position master (P2 D6, ADR-0041). */
@Getter
@Entity
@Table(name = "positions")
public class Position extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "code", nullable = false, length = 30)
    @Setter
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    @Setter
    private String name;

    @Column(name = "description", length = 255)
    @Setter
    private String description;

    @Column(name = "job_grade", length = 40)
    @Setter
    private String jobGrade;

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

    protected Position() {}

    public Position(Long companyId, String code, String name, String description,
                    String jobGrade, Long createdBy) {
        this.companyId   = companyId;
        this.code        = code;
        this.name        = name;
        this.description = description;
        this.jobGrade    = jobGrade;
        this.createdBy   = createdBy;
    }
}
