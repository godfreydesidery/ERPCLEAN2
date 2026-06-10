package com.erp.modules.tax.domain.entity;

import com.erp.modules.tax.domain.enums.WhtKind;
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

/**
 * Lean WHT rate/type master — per company (ADR-0017 D-2d).
 */
@Getter
@Entity
@Table(name = "wht_types")
public class WhtType extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "code", nullable = false, length = 30)
    @Setter
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    @Setter
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private WhtKind kind;

    @Column(name = "rate_pct", nullable = false)
    @Setter
    private BigDecimal ratePct;

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

    protected WhtType() {
        // JPA
    }

    public WhtType(Long companyId, String code, String name,
                   WhtKind kind, BigDecimal ratePct, Long createdBy) {
        this.companyId = companyId;
        this.code      = code;
        this.name      = name;
        this.kind      = kind;
        this.ratePct   = ratePct;
        this.createdBy = createdBy;
    }
}
