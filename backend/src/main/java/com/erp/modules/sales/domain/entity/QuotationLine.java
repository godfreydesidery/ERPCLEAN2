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
 * Quotation line (ADR-0021 D-3).
 */
@Getter
@Entity
@Table(name = "quotation_lines")
public class QuotationLine extends UidEntity {

    @Column(name = "quotation_id", nullable = false, updatable = false)
    private Long quotationId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "line_no", nullable = false)
    @Setter
    private short lineNo;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_code", nullable = false, length = 60)
    private String productCode;

    @Column(name = "product_name", nullable = false, length = 120)
    private String productName;

    @Column(name = "unit_id", nullable = false)
    private Long unitId;

    @Column(name = "unit_name", nullable = false, length = 60)
    private String unitName;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal quantity;

    @Column(name = "qty_in_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal qtyInBase;

    @Column(name = "list_price_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal listPriceAmount;

    @Column(name = "unit_price_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal unitPriceAmount;

    @Column(name = "line_discount_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal lineDiscountAmount;

    @Column(name = "line_discount_percent", precision = 9, scale = 4)
    @Setter
    private BigDecimal lineDiscountPercent;

    @Enumerated(EnumType.STRING)
    @Column(name = "vat_status", nullable = false, length = 20)
    private VatStatus vatStatus;

    @Column(name = "vat_rate", nullable = false, precision = 9, scale = 4)
    @Setter
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

    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    /**
     * P3 (X15): promotion provenance — soft-FK to promotions(id). Nullable; stamped when a
     * promotion was applied to this line. Scalar Long per cross-module soft-FK convention.
     */
    @Column(name = "promotion_id")
    @Setter
    private Long promotionId;

    /**
     * ADR-0056: snapshot of whether {@code unitPriceAmount} was sourced from a VAT-inclusive
     * price list at line-add time — immutable once set. {@code true} = GROSS amount, totals
     * calculator strips VAT; {@code false} (default, V86 backfill) = NET, pre-V86 behaviour.
     */
    @Column(name = "price_inclusive", nullable = false)
    @Setter
    private boolean priceInclusive = false;

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

    protected QuotationLine() {
        // JPA
    }

    public QuotationLine(Long quotationId, Long companyId, Long branchId, short lineNo,
                         Long productId, String productCode, String productName,
                         Long unitId, String unitName,
                         BigDecimal quantity, BigDecimal qtyInBase,
                         BigDecimal listPrice, BigDecimal unitPrice,
                         VatStatus vatStatus, BigDecimal vatRate,
                         String currency, Long createdBy) {
        this.quotationId = quotationId;
        this.companyId = companyId;
        this.branchId = branchId;
        this.lineNo = lineNo;
        this.productId = productId;
        this.productCode = productCode;
        this.productName = productName;
        this.unitId = unitId;
        this.unitName = unitName;
        this.quantity = quantity;
        this.qtyInBase = qtyInBase;
        this.listPriceAmount = listPrice;
        this.unitPriceAmount = unitPrice;
        this.vatStatus = vatStatus;
        this.vatRate = vatRate;
        this.currency = CurrencyCode.of(currency);
        this.createdBy = createdBy;
    }
}
