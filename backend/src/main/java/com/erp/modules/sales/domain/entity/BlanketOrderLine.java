package com.erp.modules.sales.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * One product line on a blanket order (ADR-0029 D-7).
 * drawn_qty_base <= committed_qty_base enforced by DB CHECK + optimistic lock (@Version in UidEntity).
 */
@Getter
@Entity
@Table(name = "blanket_order_lines")
public class BlanketOrderLine extends UidEntity {

    @Column(name = "blanket_order_id", nullable = false, updatable = false)
    private Long blanketOrderId;

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

    /** Committed quantity in base unit (the frame contract volume). */
    @Column(name = "committed_qty_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal committedQtyBase;

    /** Drawn quantity in base unit — incremented each time an SO is raised from this line. */
    @Column(name = "drawn_qty_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal drawnQtyBase = BigDecimal.ZERO;

    /** Agreed unit price for drawdowns. */
    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal unitPriceAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

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

    protected BlanketOrderLine() {}

    public BlanketOrderLine(Long blanketOrderId, Long companyId, Long branchId, short lineNo,
                            Long productId, String productCode, String productName,
                            Long unitId, String unitName,
                            BigDecimal committedQtyBase, BigDecimal unitPriceAmount,
                            String currency, Long createdBy) {
        this.blanketOrderId   = blanketOrderId;
        this.companyId        = companyId;
        this.branchId         = branchId;
        this.lineNo           = lineNo;
        this.productId        = productId;
        this.productCode      = productCode;
        this.productName      = productName;
        this.unitId           = unitId;
        this.unitName         = unitName;
        this.committedQtyBase = committedQtyBase;
        this.unitPriceAmount  = unitPriceAmount;
        this.currency         = CurrencyCode.of(currency);
        this.createdBy        = createdBy;
    }

    /** Remaining balance available for drawdown. */
    public BigDecimal remainingQtyBase() {
        return committedQtyBase.subtract(drawnQtyBase);
    }
}
