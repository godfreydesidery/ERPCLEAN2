package com.erp.modules.products.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.domain.MasterStatus;
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
 * Quantity-break tier on a (product, price_list) price (ADR-0029 D-6, FR-SD-09).
 *
 * <p>References the parent price by (product_id, price_list_id) scalar FKs — the natural key of
 * ProductPrice (which has no uid). company_id denormalised for scoped reads.
 * Sorted ascending by min_qty; the resolver picks the greatest min_qty <= lineQty.
 */
@Getter
@Entity
@Table(name = "price_tiers")
public class PriceTier extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** FK → products.id; scalar — no cross-module @ManyToOne. */
    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    /** FK → price_lists.id; scalar. */
    @Column(name = "price_list_id", nullable = false, updatable = false)
    private Long priceListId;

    /** Quantity break floor (inclusive), in the product's sell unit. CHECK > 0. */
    @Column(name = "min_qty", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal minQty;

    /** The tier unit price for lines at >= minQty. CHECK >= 0. */
    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal unitPriceAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

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

    protected PriceTier() {
        // JPA
    }

    public PriceTier(Long companyId, Long productId, Long priceListId,
                     BigDecimal minQty, BigDecimal unitPriceAmount,
                     String currency, Long createdBy) {
        this.companyId       = companyId;
        this.productId       = productId;
        this.priceListId     = priceListId;
        this.minQty          = minQty;
        this.unitPriceAmount = unitPriceAmount;
        this.currency        = CurrencyCode.ofNullable(currency);
        this.createdBy       = createdBy;
    }
}
