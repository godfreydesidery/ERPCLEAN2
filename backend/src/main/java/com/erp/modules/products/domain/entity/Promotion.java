package com.erp.modules.products.domain.entity;

import com.erp.modules.products.domain.enums.PromotionEffect;
import com.erp.modules.products.domain.enums.PromotionTarget;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * A time-boxed promotion / discount rule (ADR-0029 D-6, FR-SD-11).
 *
 * <p>Targets a product, a product category string, or ALL products within the company.
 * Applies a % or fixed-amount discount, or an override price, within its date window.
 * Multiple promotions matching the same line are resolved by deterministic precedence
 * (highest priority, tiebreak lowest uid — OQ-SD-03, BR-SD-09).
 */
@Getter
@Entity
@Table(name = "promotions")
public class Promotion extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "code", nullable = false, length = 30)
    @Setter
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    @Setter
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "target", nullable = false, length = 20)
    @Setter
    private PromotionTarget target;

    /** FK → products.id; set when target = PRODUCT. */
    @Column(name = "target_product_id")
    @Setter
    private Long targetProductId;

    /** Product category string; set when target = CATEGORY. */
    @Column(name = "target_category", length = 60)
    @Setter
    private String targetCategory;

    @Enumerated(EnumType.STRING)
    @Column(name = "effect", nullable = false, length = 20)
    @Setter
    private PromotionEffect effect;

    /**
     * The numeric effect value: a percent (0–100) when PERCENT_DISCOUNT, a fixed amount when
     * AMOUNT_DISCOUNT, or the override price when OVERRIDE_PRICE. CHECK >= 0.
     */
    @Column(name = "effect_value", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal effectValue;

    @Column(name = "effective_from", nullable = false)
    @Setter
    private LocalDate effectiveFrom;

    @Column(name = "effective_to", nullable = false)
    @Setter
    private LocalDate effectiveTo;

    /** Tiebreak: higher priority wins when multiple promotions match the same line. */
    @Column(name = "priority", nullable = false)
    @Setter
    private short priority = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
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

    protected Promotion() {
        // JPA
    }

    public Promotion(Long companyId, String code, String name,
                     PromotionTarget target, Long targetProductId, String targetCategory,
                     PromotionEffect effect, BigDecimal effectValue,
                     LocalDate effectiveFrom, LocalDate effectiveTo,
                     short priority, Long createdBy) {
        this.companyId       = companyId;
        this.code            = code;
        this.name            = name;
        this.target          = target;
        this.targetProductId = targetProductId;
        this.targetCategory  = targetCategory;
        this.effect          = effect;
        this.effectValue     = effectValue;
        this.effectiveFrom   = effectiveFrom;
        this.effectiveTo     = effectiveTo;
        this.priority        = priority;
        this.createdBy       = createdBy;
    }
}
