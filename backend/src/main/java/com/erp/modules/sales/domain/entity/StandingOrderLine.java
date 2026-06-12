package com.erp.modules.sales.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * One product line on a standing order template (ADR-0029 D-8).
 * Copied verbatim into the generated SalesOrderLine on each run.
 */
@Getter
@Entity
@Table(name = "standing_order_lines")
public class StandingOrderLine extends UidEntity {

    @Column(name = "standing_order_id", nullable = false, updatable = false)
    private Long standingOrderId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "line_no", nullable = false)
    private short lineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "product_code", nullable = false, length = 60)
    private String productCode;

    @Column(name = "product_name", nullable = false, length = 120)
    private String productName;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private Long unitId;

    @Column(name = "unit_name", nullable = false, length = 60)
    private String unitName;

    @Column(name = "qty", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal qty;

    @Column(name = "qty_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal qtyBase;

    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal unitPriceAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

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

    protected StandingOrderLine() {}

    public StandingOrderLine(Long standingOrderId, Long companyId, Long branchId, short lineNo,
                             Long productId, String productCode, String productName,
                             Long unitId, String unitName,
                             BigDecimal qty, BigDecimal qtyBase,
                             BigDecimal unitPriceAmount, String currency, Long createdBy) {
        this.standingOrderId = standingOrderId;
        this.companyId       = companyId;
        this.branchId        = branchId;
        this.lineNo          = lineNo;
        this.productId       = productId;
        this.productCode     = productCode;
        this.productName     = productName;
        this.unitId          = unitId;
        this.unitName        = unitName;
        this.qty             = qty;
        this.qtyBase         = qtyBase;
        this.unitPriceAmount = unitPriceAmount;
        this.currency        = currency;
        this.createdBy       = createdBy;
    }
}
