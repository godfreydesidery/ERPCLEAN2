package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.SalesOrderStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * Sales order header (ADR-0021 D-3).
 *
 * <p>SO-#### allocated at create. Lifecycle managed by SalesOrderService.
 * Extends UidEntity (id + uid + @Version optimistic lock).
 */
@Getter
@Entity
@Table(name = "sales_orders")
public class SalesOrder extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "order_number", nullable = false, length = 30)
    @Setter
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Setter
    private SalesOrderStatus status = SalesOrderStatus.DRAFT;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "agent_id")
    @Setter
    private Long agentId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Column(name = "source_quotation_uid", length = 26)
    @Setter
    private String sourceQuotationUid;

    /** Back-link to the CRM opportunity that converted to this SO (ADR-0031 D-7, V52). Nullable. */
    @Column(name = "source_opportunity_uid", length = 26)
    @Setter
    private String sourceOpportunityUid;

    // --- sales-depth (ADR-0029 D-7/D-8, V45) --- blanket/standing back-links ---

    /** UID of the blanket order this SO draws from (ADR-0029 D-7). Nullable. */
    @Column(name = "source_blanket_uid", length = 26)
    @Setter
    private String sourceBlanketUid;

    /** UID of the standing order that generated this SO (ADR-0029 D-8). Nullable. */
    @Column(name = "source_standing_uid", length = 26)
    @Setter
    private String sourceStandingUid;

    @Column(name = "doc_discount_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal docDiscountAmount;

    @Column(name = "doc_discount_percent", precision = 9, scale = 4)
    @Setter
    private BigDecimal docDiscountPercent;

    @Column(name = "net_total_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal netTotalAmount = BigDecimal.ZERO;

    @Column(name = "vat_total_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal vatTotalAmount = BigDecimal.ZERO;

    @Column(name = "gross_total_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal grossTotalAmount = BigDecimal.ZERO;

    @Column(name = "confirmed_at")
    @Setter
    private Instant confirmedAt;

    @Column(name = "cancelled_at")
    @Setter
    private Instant cancelledAt;

    @Column(name = "cancel_reason", length = 255)
    @Setter
    private String cancelReason;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

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

    protected SalesOrder() {
        // JPA
    }

    public SalesOrder(Long companyId, Long branchId, Long customerId, Long agentId,
                      String currency, LocalDate orderDate, Long createdBy) {
        this.companyId = companyId;
        this.branchId = branchId;
        this.customerId = customerId;
        this.agentId = agentId;
        this.currency = currency;
        this.orderDate = orderDate;
        this.createdBy = createdBy;
    }
}
