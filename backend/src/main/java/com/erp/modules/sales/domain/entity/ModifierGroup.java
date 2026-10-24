package com.erp.modules.sales.domain.entity;

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

/**
 * A choice offered on menu items — "Milk", "Extras", "Cooking" (ADR-0066 D-4). {@code minSelect}
 * and {@code maxSelect} are the rule of the choice (Milk: exactly one; Extras: any number, so no
 * maximum). Company-wide; attached to items through {@link ProductModifierGroup}.
 */
@Getter
@Entity
@Table(name = "modifier_groups")
public class ModifierGroup extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "name", nullable = false, length = 60)
    @Setter
    private String name;

    @Column(name = "min_select", nullable = false)
    @Setter
    private Short minSelect = 0;

    /** Null = no upper limit. */
    @Column(name = "max_select")
    @Setter
    private Short maxSelect;

    @Column(name = "sort_order", nullable = false)
    @Setter
    private Integer sortOrder = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    protected ModifierGroup() {}

    public ModifierGroup(Long companyId, String name, Long createdBy) {
        this.companyId = companyId;
        this.name      = name;
        this.createdBy = createdBy;
    }
}
