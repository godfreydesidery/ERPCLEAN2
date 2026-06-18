package com.erp.modules.products.domain.entity;

import com.erp.platform.common.domain.Ulid;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/**
 * Append-only redemption log for a {@link Promotion} (P2 D5, ADR-0041 D5).
 *
 * <p>One row per redemption — used for {@code usage_limit} enforcement and reporting. Does NOT
 * extend {@link com.erp.platform.common.domain.UidEntity}: like {@link ProductBarcode}, rows are
 * insert-only with no optimistic-lock {@code version}. Carries a {@code uid} for external addressing.
 * {@code promotion_id} is a scalar soft-FK to keep the owned-child shape consistent with the rest of
 * the module.
 */
@Getter
@Entity
@Table(name = "promotion_usages")
public class PromotionUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, updatable = false, length = 26)
    private String uid;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** Scalar FK → promotions(id); the redeemed promotion. */
    @Column(name = "promotion_id", nullable = false, updatable = false)
    private Long promotionId;

    /** Soft-FK → customers(id); the redeeming customer (nullable for anonymous/walk-in). */
    @Column(name = "customer_id")
    private Long customerId;

    /** Soft-FK → app_users(id); the operator who applied the promotion. */
    @Column(name = "used_by")
    private Long usedBy;

    @Column(name = "used_at", nullable = false, updatable = false)
    private Instant usedAt = Instant.now();

    /** The sales document the promotion was applied to. */
    @Column(name = "order_uid", length = 26)
    private String orderUid;

    /** Discount amount granted on this redemption. */
    @Column(name = "amount", precision = 19, scale = 4)
    private BigDecimal amount;

    @PrePersist
    void assignUid() {
        if (uid == null) {
            uid = Ulid.next();
        }
    }

    protected PromotionUsage() {
        // JPA
    }

    public PromotionUsage(Long companyId, Long promotionId, Long customerId,
                          Long usedBy, String orderUid, BigDecimal amount) {
        this.companyId   = companyId;
        this.promotionId = promotionId;
        this.customerId  = customerId;
        this.usedBy      = usedBy;
        this.orderUid    = orderUid;
        this.amount      = amount;
    }
}
