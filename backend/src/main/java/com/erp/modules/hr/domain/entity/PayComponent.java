package com.erp.modules.hr.domain.entity;

import com.erp.modules.hr.domain.enums.PayComponentBasis;
import com.erp.modules.hr.domain.enums.PayComponentKind;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** Pay component catalogue entry (ADR-0032 D-4). */
@Getter
@Entity
@Table(name = "pay_components")
public class PayComponent extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "code", nullable = false, length = 30)
    @Setter
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    @Setter
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 10)
    private PayComponentKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "basis", nullable = false, length = 20)
    @Setter
    private PayComponentBasis basis;

    @Column(name = "gl_account_id", nullable = false)
    @Setter
    private Long glAccountId;

    @Column(name = "taxable", nullable = false)
    @Setter
    private boolean taxable = true;

    @Column(name = "pensionable", nullable = false)
    @Setter
    private boolean pensionable = true;

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

    protected PayComponent() {}

    public PayComponent(Long companyId, String code, String name,
                        PayComponentKind kind, PayComponentBasis basis,
                        Long glAccountId, boolean taxable, boolean pensionable, Long createdBy) {
        this.companyId    = companyId;
        this.code         = code;
        this.name         = name;
        this.kind         = kind;
        this.basis        = basis;
        this.glAccountId  = glAccountId;
        this.taxable      = taxable;
        this.pensionable  = pensionable;
        this.createdBy    = createdBy;
    }
}
