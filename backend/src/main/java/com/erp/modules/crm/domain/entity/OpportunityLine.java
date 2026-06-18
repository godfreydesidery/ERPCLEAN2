package com.erp.modules.crm.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Estimated line on a CRM opportunity (ADR-0031 D-5).
 * No VAT/net/gross computation — CRM passes estimates; the O2C InvoiceTotalsCalculator computes
 * real totals on convert (NFR-CRM-03).
 */
@Getter
@Entity
@Table(name = "opportunity_lines")
public class OpportunityLine extends UidEntity {

    @Column(name = "opportunity_id", nullable = false, updatable = false)
    private Long opportunityId;

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

    @Column(name = "estimated_qty", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal estimatedQty;

    @Column(name = "estimated_unit_price_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal estimatedUnitPriceAmount = BigDecimal.ZERO;

    @Column(name = "line_discount_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal lineDiscountAmount;

    @Column(name = "line_discount_percent", precision = 9, scale = 4)
    @Setter
    private BigDecimal lineDiscountPercent;

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

    protected OpportunityLine() { /* JPA */ }

    public OpportunityLine(Long opportunityId, Long companyId, Long branchId,
                           short lineNo, Long productId, String productCode, String productName,
                           Long unitId, String unitName, BigDecimal estimatedQty,
                           BigDecimal estimatedUnitPriceAmount, String currency, Long createdBy) {
        this.opportunityId = opportunityId;
        this.companyId = companyId;
        this.branchId = branchId;
        this.lineNo = lineNo;
        this.productId = productId;
        this.productCode = productCode;
        this.productName = productName;
        this.unitId = unitId;
        this.unitName = unitName;
        this.estimatedQty = estimatedQty;
        this.estimatedUnitPriceAmount = estimatedUnitPriceAmount;
        this.currency = CurrencyCode.ofNullable(currency);
        this.createdBy = createdBy;
    }
}
