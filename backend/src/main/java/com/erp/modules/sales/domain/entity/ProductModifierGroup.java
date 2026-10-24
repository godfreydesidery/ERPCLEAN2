package com.erp.modules.sales.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * Which modifier groups a menu item offers (ADR-0066 D-4). A junction: no uid, never addressed on
 * its own — the setup screen replaces a group's whole item list in one save.
 */
@Getter
@Entity
@Table(name = "product_modifier_group")
public class ProductModifierGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "modifier_group_id", nullable = false, updatable = false)
    private Long modifierGroupId;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    protected ProductModifierGroup() {}

    public ProductModifierGroup(Long companyId, Long productId, Long modifierGroupId, Long createdBy) {
        this.companyId       = companyId;
        this.productId       = productId;
        this.modifierGroupId = modifierGroupId;
        this.createdBy       = createdBy;
    }
}
