package com.erp.modules.products.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Single-level BOM component entry (FR-PROD-14, ADR-0007 D-8).
 * Self-referential: both FKs point to products.
 * No uid — components are addressed via composed_product_uid + component_product_uid.
 * Self-composition prevented by DB CHECK chk_product_component_not_self (BR-PROD-05).
 * Same-company guard is in the service (BR-PROD-06).
 */
@Getter
@Entity
@Table(name = "product_components")
public class ProductComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "composed_product_id", nullable = false, updatable = false)
    private Product composedProduct;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_product_id", nullable = false, updatable = false)
    private Product componentProduct;

    /** How many base units of the component per one composed product; > 0 (DB CHECK). */
    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal quantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected ProductComponent() {
        // JPA
    }

    public ProductComponent(Product composedProduct, Product componentProduct,
                            BigDecimal quantity, Long createdBy) {
        this.composedProduct = composedProduct;
        this.componentProduct = componentProduct;
        this.quantity = quantity;
        this.createdBy = createdBy;
    }
}
