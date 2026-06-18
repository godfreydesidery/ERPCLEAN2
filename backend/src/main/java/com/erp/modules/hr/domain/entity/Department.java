package com.erp.modules.hr.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Per-company department master (ADR-0032 D-4). */
@Getter
@Entity
@Table(name = "departments")
public class Department extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "code", nullable = false, length = 30)
    @Setter
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    @Setter
    private String name;

    @Column(name = "active", nullable = false)
    @Setter
    private boolean active = true;

    // ---- Org hierarchy + ownership (P2 D6, ADR-0041) — all soft-FK scalars ----

    /** Self soft-FK departments — flat-tolerant (no DB FK). */
    @Column(name = "parent_department_id")
    @Setter
    private Long parentDepartmentId;

    /** Soft-FK employees. */
    @Column(name = "manager_id")
    @Setter
    private Long managerId;

    /** Soft-FK gl dimension_values (cost centre). */
    @Column(name = "cost_centre_value_id")
    @Setter
    private Long costCentreValueId;

    /** Soft-FK branches. */
    @Column(name = "branch_id")
    @Setter
    private Long branchId;

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

    protected Department() {}

    public Department(Long companyId, String code, String name, Long createdBy) {
        this.companyId = companyId;
        this.code      = code;
        this.name      = name;
        this.createdBy = createdBy;
    }
}
