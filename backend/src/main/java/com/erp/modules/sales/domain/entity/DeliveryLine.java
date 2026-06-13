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
 * Delivery line (ADR-0021 D-7).
 *
 * <p>issue_value_amount stores the COGS value issued for this line (sum of components), used as
 * the cost basis anchor for returns (OQ-SO-05).
 */
@Getter
@Entity
@Table(name = "delivery_lines")
public class DeliveryLine extends UidEntity {

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private Long deliveryId;

    @Column(name = "sales_order_line_id", nullable = false, updatable = false)
    private Long salesOrderLineId;

    @Column(name = "sales_order_line_uid", nullable = false, length = 26, updatable = false)
    private String salesOrderLineUid;

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

    @Column(name = "qty_delivered", nullable = false, precision = 19, scale = 6)
    private BigDecimal qtyDelivered;

    @Column(name = "qty_delivered_base", nullable = false, precision = 19, scale = 6)
    private BigDecimal qtyDeliveredBase;

    /** Running Σ invoiced from this delivery line (partial invoicing, D-10). */
    @Column(name = "qty_invoiced_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal qtyInvoicedBase = BigDecimal.ZERO;

    /** Running Σ returned against this delivery line (Stage 2, D-11). */
    @Column(name = "returned_qty_base", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal returnedQtyBase = BigDecimal.ZERO;

    /**
     * COGS value the delivery issued for this line (D-7).
     * NULL when avg_cost was not established at delivery time (no COGS posted).
     * Used as the cost-basis anchor for returns (OQ-SO-05).
     */
    @Column(name = "issue_value_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal issueValueAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    // --- projects (ADR-0033 D-3, V66) ---
    @Column(name = "project_id")
    @Setter
    private Long projectId;

    @Column(name = "project_task_id")
    @Setter
    private Long projectTaskId;

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

    protected DeliveryLine() {
        // JPA
    }

    public DeliveryLine(Long deliveryId, Long salesOrderLineId, String salesOrderLineUid,
                        Long companyId, Long branchId, short lineNo,
                        Long productId, String productCode, String productName,
                        Long unitId, String unitName,
                        BigDecimal qtyDelivered, BigDecimal qtyDeliveredBase,
                        String currency, Long createdBy) {
        this.deliveryId = deliveryId;
        this.salesOrderLineId = salesOrderLineId;
        this.salesOrderLineUid = salesOrderLineUid;
        this.companyId = companyId;
        this.branchId = branchId;
        this.lineNo = lineNo;
        this.productId = productId;
        this.productCode = productCode;
        this.productName = productName;
        this.unitId = unitId;
        this.unitName = unitName;
        this.qtyDelivered = qtyDelivered;
        this.qtyDeliveredBase = qtyDeliveredBase;
        this.currency = currency;
        this.createdBy = createdBy;
    }

    /** Derived uninvoiced balance for this delivery line. */
    public BigDecimal openInvoiceQtyBase() {
        return qtyDeliveredBase.subtract(qtyInvoicedBase);
    }
}
