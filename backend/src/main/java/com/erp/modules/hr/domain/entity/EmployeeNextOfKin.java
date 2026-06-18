package com.erp.modules.hr.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Next-of-kin child record owned by Employee (ADR-0040 D-11).
 * Partial-unique index enforces at most one is_primary=true per employee.
 */
@Getter
@Entity
@Table(name = "employee_next_of_kin")
public class EmployeeNextOfKin extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private Long employeeId;

    @Column(name = "name", nullable = false, length = 120)
    @Setter
    private String name;

    @Column(name = "relationship", length = 60)
    @Setter
    private String relationship;

    @Column(name = "phone", length = 40)
    @Setter
    private String phone;

    @Column(name = "email", length = 160)
    @Setter
    private String email;

    @Column(name = "is_primary", nullable = false)
    @Setter
    private boolean isPrimary = false;

    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private String status = "ACTIVE";

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

    protected EmployeeNextOfKin() {}

    public EmployeeNextOfKin(Long companyId, Long employeeId, String name, Long createdBy) {
        this.companyId  = companyId;
        this.employeeId = employeeId;
        this.name       = name;
        this.createdBy  = createdBy;
    }
}
