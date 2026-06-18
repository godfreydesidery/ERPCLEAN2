package com.erp.modules.sales.domain.entity;

import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
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
 * Sales return line (ADR-0021 D-11, Stage 2).
 *
 * <p>One line per delivery line being returned. Carries the pricing/VAT snapshots from the
 * delivery's SO line (for the credit-note value). qty_returned_base drives the stock-in and
 * COGS-reversal apportionment.
 */
@Getter
@Entity
@Table(name = "sales_return_lines")
public class SalesReturnLine extends UidEntity {

    @Column(name = "sales_return_id", nullable = false, updatable = false)
    private Long salesReturnId;

    @Column(name = "delivery_line_id", nullable = false, updatable = false)
    private Long deliveryLineId;

    @Column(name = "delivery_line_uid", nullable = false, length = 26, updatable = false)
    private String deliveryLineUid;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "line_no", nullable = false)
    @Setter
    private short lineNo;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "product_code", nullable = false, length = 60, updatable = false)
    private String productCode;

    @Column(name = "product_name", nullable = false, length = 120, updatable = false)
    private String productName;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private Long unitId;

    @Column(name = "unit_name", nullable = false, length = 60, updatable = false)
    private String unitName;

    @Column(name = "qty_returned", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal qtyReturned;

    @Column(name = "qty_returned_base", nullable = false, precision = 19, scale = 6, updatable = false)
    private BigDecimal qtyReturnedBase;

    /** Unit price from the SO line — drives the credit-note net. */
    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal unitPriceAmount;

    @Column(name = "line_discount_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal lineDiscountAmount;

    @Column(name = "line_discount_percent", precision = 9, scale = 4, updatable = false)
    private BigDecimal lineDiscountPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "vat_status", nullable = false, length = 20, updatable = false)
    private VatStatus vatStatus;

    @Column(name = "vat_rate", nullable = false, precision = 9, scale = 4, updatable = false)
    private BigDecimal vatRate;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
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

    protected SalesReturnLine() {
        // JPA
    }

    public SalesReturnLine(Long salesReturnId, Long deliveryLineId, String deliveryLineUid,
                           Long companyId, Long branchId, short lineNo,
                           Long productId, String productCode, String productName,
                           Long unitId, String unitName,
                           BigDecimal qtyReturned, BigDecimal qtyReturnedBase,
                           BigDecimal unitPriceAmount,
                           BigDecimal lineDiscountAmount, BigDecimal lineDiscountPercent,
                           VatStatus vatStatus, BigDecimal vatRate,
                           String currency, Long createdBy) {
        this.salesReturnId       = salesReturnId;
        this.deliveryLineId      = deliveryLineId;
        this.deliveryLineUid     = deliveryLineUid;
        this.companyId           = companyId;
        this.branchId            = branchId;
        this.lineNo              = lineNo;
        this.productId           = productId;
        this.productCode         = productCode;
        this.productName         = productName;
        this.unitId              = unitId;
        this.unitName            = unitName;
        this.qtyReturned         = qtyReturned;
        this.qtyReturnedBase     = qtyReturnedBase;
        this.unitPriceAmount     = unitPriceAmount;
        this.lineDiscountAmount  = lineDiscountAmount;
        this.lineDiscountPercent = lineDiscountPercent;
        this.vatStatus           = vatStatus;
        this.vatRate             = vatRate;
        this.currency            = CurrencyCode.of(currency);
        this.createdBy           = createdBy;
    }
}
