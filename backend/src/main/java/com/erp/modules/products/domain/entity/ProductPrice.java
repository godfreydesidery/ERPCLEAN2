package com.erp.modules.products.domain.entity;

import com.erp.platform.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A product's price on a named price list (FR-PROD-10/11, ADR-0007 D-7).
 * Uses bare {@code @Embedded Money} — columns are {@code amount}/{@code currency} matching
 * Money's own default column names (no {@code @AttributeOverride} needed).
 * company_id is DENORMALISED from the product for scoped reads (set once, immutable).
 *
 * <p>No uid — price rows are addressed via product_uid + price_list_uid in the API.
 */
@Getter
@Entity
@Table(name = "product_prices")
public class ProductPrice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_list_id", nullable = false, updatable = false)
    private PriceList priceList;

    /**
     * Denormalised company_id — set from product.companyId at create; immutable.
     */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /**
     * The price — bare @Embedded, columns are "amount" and "currency" per Money defaults.
     * NOT NULL together (the row exists only to hold a price — no null-together CHECK needed).
     */
    @Embedded
    @Setter
    private Money price;

    /** Price validity start (P2-M3). Nullable. */
    @Column(name = "effective_from")
    @Setter
    private java.time.LocalDate effectiveFrom;

    /** Price validity end (P2-M3). Nullable. */
    @Column(name = "effective_to")
    @Setter
    private java.time.LocalDate effectiveTo;

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

    protected ProductPrice() {
        // JPA
    }

    public ProductPrice(Product product, PriceList priceList, Money price, Long createdBy) {
        this.product = product;
        this.priceList = priceList;
        this.companyId = product.getCompanyId();  // denormalise at construction
        this.price = price;
        this.createdBy = createdBy;
    }
}
