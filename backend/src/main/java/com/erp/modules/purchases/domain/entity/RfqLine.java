package com.erp.modules.purchases.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * RFQ line — the product+quantity lines to solicit (ADR-0027 D-4).
 */
@Getter
@Entity
@Table(name = "rfq_lines")
public class RfqLine extends UidEntity {

    @Column(name = "rfq_id", nullable = false, updatable = false)
    private Long rfqId;

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

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "unit_id", nullable = false, updatable = false)
    private Long unitId;

    @Column(name = "unit_name", nullable = false, length = 60)
    private String unitName;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal quantity;

    @Column(name = "quantity_in_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal quantityInBase;

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

    protected RfqLine() {
        // JPA
    }

    public RfqLine(Long rfqId, Long companyId, Long branchId, short lineNo,
                   Long productId, String productCode, String productName,
                   Long unitId, String unitName,
                   BigDecimal quantity, BigDecimal quantityInBase, Long createdBy) {
        this.rfqId          = rfqId;
        this.companyId      = companyId;
        this.branchId       = branchId;
        this.lineNo         = lineNo;
        this.productId      = productId;
        this.productCode    = productCode;
        this.productName    = productName;
        this.unitId         = unitId;
        this.unitName       = unitName;
        this.quantity       = quantity;
        this.quantityInBase = quantityInBase;
        this.createdBy      = createdBy;
    }
}
