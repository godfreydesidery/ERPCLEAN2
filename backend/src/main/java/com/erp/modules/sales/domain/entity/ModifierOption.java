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
 * One option of a {@link ModifierGroup} (ADR-0066 D-4).
 *
 * <p>With a {@code productId} it is a <b>priced add-on</b>: choosing it adds a child line for that
 * product to the order, priced, taxed and stock-deducted like any line ("Syrup extra"). Without one
 * it is an <b>instruction</b> ("Oat milk", "No onions") that goes into the item's note and costs
 * nothing. Options are deactivated, never deleted, so past orders keep their meaning.
 */
@Getter
@Entity
@Table(name = "modifier_options")
public class ModifierOption extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "modifier_group_id", nullable = false, updatable = false)
    private Long modifierGroupId;

    @Column(name = "label", nullable = false, length = 60)
    @Setter
    private String label;

    @Column(name = "product_id")
    @Setter
    private Long productId;

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

    protected ModifierOption() {}

    public ModifierOption(Long companyId, Long modifierGroupId, String label, Long createdBy) {
        this.companyId       = companyId;
        this.modifierGroupId = modifierGroupId;
        this.label           = label;
        this.createdBy       = createdBy;
    }
}
