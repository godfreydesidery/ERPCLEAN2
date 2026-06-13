package com.erp.modules.products.domain.entity;

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
 * A customer-specific contract price for a (customer, product) pair (ADR-0029 D-6, FR-SD-10).
 *
 * <p>Overrides the customer's assigned price-list price (incl. tiers) when resolving for that
 * customer (BR-SD-08). One active row per (customer_id, product_id) — enforced by
 * uq_customer_price_scope. Window columns narrow the applicability date range.
 */
@Getter
@Entity
@Table(name = "customer_prices")
public class CustomerPrice extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** FK → customers.id; scalar — no cross-module @ManyToOne. */
    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    /** FK → products.id; scalar. */
    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    /** The contract unit price. CHECK >= 0. */
    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal unitPriceAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** NULL = no start date restriction. */
    @Column(name = "effective_from")
    @Setter
    private LocalDate effectiveFrom;

    /** NULL = no end date restriction. */
    @Column(name = "effective_to")
    @Setter
    private LocalDate effectiveTo;

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

    protected CustomerPrice() {
        // JPA
    }

    public CustomerPrice(Long companyId, Long customerId, Long productId,
                         BigDecimal unitPriceAmount, String currency,
                         LocalDate effectiveFrom, LocalDate effectiveTo,
                         Long createdBy) {
        this.companyId       = companyId;
        this.customerId      = customerId;
        this.productId       = productId;
        this.unitPriceAmount = unitPriceAmount;
        this.currency        = currency;
        this.effectiveFrom   = effectiveFrom;
        this.effectiveTo     = effectiveTo;
        this.createdBy       = createdBy;
    }
}
