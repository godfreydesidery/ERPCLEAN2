package com.erp.modules.hr.domain.entity;

import com.erp.modules.hr.domain.enums.LeaveAccrualMethod;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Leave type master (ADR-0032 D-4). */
@Getter
@Entity
@Table(name = "leave_types")
public class LeaveType extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "code", nullable = false, length = 30)
    @Setter
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    @Setter
    private String name;

    @Column(name = "is_paid", nullable = false)
    @Setter
    private boolean paid = true;

    @Column(name = "annual_entitlement_days", nullable = false, precision = 9, scale = 2)
    @Setter
    private BigDecimal annualEntitlementDays = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "accrual_method", nullable = false, length = 16)
    @Setter
    private LeaveAccrualMethod accrualMethod = LeaveAccrualMethod.ANNUAL_GRANT;

    @Column(name = "active", nullable = false)
    @Setter
    private boolean active = true;

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

    protected LeaveType() {}

    public LeaveType(Long companyId, String code, String name, boolean paid,
                     BigDecimal annualEntitlementDays, LeaveAccrualMethod accrualMethod, Long createdBy) {
        this.companyId             = companyId;
        this.code                  = code;
        this.name                  = name;
        this.paid                  = paid;
        this.annualEntitlementDays = annualEntitlementDays;
        this.accrualMethod         = accrualMethod;
        this.createdBy             = createdBy;
    }
}
