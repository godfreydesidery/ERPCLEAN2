package com.erp.modules.products.domain.entity;

import com.erp.modules.products.domain.enums.ComponentSourcing;
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
 * One input line under a {@link Bom} header (ADR-0026 D-3, FR-BOM-07..11, BR-BOM-02/03/06/07/08).
 *
 * <p>The {@code component_product_id} is a scalar FK (never a {@code @ManyToOne} load) to keep
 * the explosion batch-friendly — components are fetched by bom_id in one query per level (D-6 N+1
 * note). Snapshot columns ({@code component_product_code}, {@code component_product_name}) mirror
 * the sales-line snapshot pattern: display without an extra join.
 *
 * <p>The {@code sourcing} flag determines explosion behaviour: {@link ComponentSourcing#MAKE}
 * recurses into the child's ACTIVE BOM; {@link ComponentSourcing#BUY} is a leaf (BR-BOM-07).
 * A DRAFT header's component set is freely editable; an ACTIVE header's is frozen (BR-BOM-03).
 */
@Getter
@Entity
@Table(name = "bom_components")
public class BomComponent extends UidEntity {

    /** FK → boms.id; never updated (move to a new version instead). */
    @Column(name = "bom_id", nullable = false, updatable = false)
    private Long bomId;

    /** Denormalised tenant — the tenant-predicate-without-join pattern. Never updated. */
    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** Display order within the BOM; unique per bom_id (uq_bom_component_line_no). */
    @Column(name = "line_no", nullable = false)
    @Setter
    private short lineNo;

    /** Scalar FK → products.id; never updated (change = new version). */
    @Column(name = "component_product_id", nullable = false, updatable = false)
    private Long componentProductId;

    /** Snapshot of component product code at add-time (display without join). */
    @Column(name = "component_product_code", nullable = false, length = 20)
    private String componentProductCode;

    /** Snapshot of component product name at add-time. */
    @Column(name = "component_product_name", nullable = false, length = 200)
    private String componentProductName;

    /**
     * Child base-units per one execution of the parent's output_qty (BR-BOM-06).
     * Must be > 0 (chk_bom_component_qty).
     */
    @Column(name = "qty_per", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal qtyPer;

    /**
     * Make/buy classification (FR-BOM-08, BR-BOM-07).
     * MAKE ⇒ recurse during explosion; BUY ⇒ leaf.
     * Stored classification wins even if the child later gains/loses a BOM.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "sourcing", nullable = false, length = 10)
    @Setter
    private ComponentSourcing sourcing = ComponentSourcing.BUY;

    /**
     * Line-level scrap % (BR-BOM-08). Default 0 = no inflation.
     * Applied as {@code ÷ (1 − scrap_percent/100)} during explosion (D-4).
     */
    @Column(name = "scrap_percent", nullable = false, precision = 9, scale = 4)
    @Setter
    private BigDecimal scrapPercent = BigDecimal.ZERO;

    /** Reference designator / engineering note (FR-BOM-07). */
    @Column(name = "reference", length = 120)
    @Setter
    private String reference;

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

    protected BomComponent() {
        // JPA
    }

    public BomComponent(Long bomId, Long companyId, short lineNo,
                        Long componentProductId, String componentProductCode, String componentProductName,
                        BigDecimal qtyPer, ComponentSourcing sourcing, BigDecimal scrapPercent,
                        String reference, Long createdBy) {
        this.bomId = bomId;
        this.companyId = companyId;
        this.lineNo = lineNo;
        this.componentProductId = componentProductId;
        this.componentProductCode = componentProductCode;
        this.componentProductName = componentProductName;
        this.qtyPer = qtyPer;
        this.sourcing = sourcing != null ? sourcing : ComponentSourcing.BUY;
        this.scrapPercent = scrapPercent != null ? scrapPercent : BigDecimal.ZERO;
        this.reference = reference;
        this.createdBy = createdBy;
    }
}
