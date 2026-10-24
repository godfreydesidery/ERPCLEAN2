package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.RestaurantOrderStatus;
import com.erp.modules.sales.domain.enums.RestaurantOrderType;
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
 * A restaurant order — the "check" (ADR-0065 D-5). Lives on the server so any device can serve it
 * and a till restart loses nothing. Carries no prices: pricing stays server-authoritative and is
 * resolved at bill time and again at payment, when the order becomes an ordinary POS sale.
 *
 * <p>State transitions are behaviour methods here; the service decides WHEN they are allowed.
 */
@Getter
@Entity
@Table(name = "restaurant_orders")
public class RestaurantOrder extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** {@code ORD-####}, per company. */
    @Column(name = "order_number", nullable = false, length = 30, updatable = false)
    private String orderNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 20)
    private RestaurantOrderType orderType = RestaurantOrderType.DINE_IN;

    /** Required for DINE_IN ({@code chk_restaurant_order_table_needed}); null for TAKEAWAY. */
    @Column(name = "restaurant_table_id")
    private Long restaurantTableId;

    @Column(name = "guest_count")
    @Setter
    private Short guestCount;

    /** Optional; the till's walk-in customer is used at payment when null. */
    @Column(name = "customer_id")
    @Setter
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RestaurantOrderStatus status = RestaurantOrderStatus.OPEN;

    /** The server / waiter who opened the order. */
    @Column(name = "opened_by", nullable = false, updatable = false)
    private Long openedBy;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "billed_at")
    private Instant billedAt;

    /** The gross total the customer was shown on the last printed bill. */
    @Column(name = "billed_total_amount", precision = 19, scale = 4)
    private BigDecimal billedTotalAmount;

    @Column(name = "bill_print_count", nullable = false)
    private Integer billPrintCount = 0;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "sales_invoice_id")
    private Long salesInvoiceId;

    @Column(name = "sales_invoice_uid", length = 26)
    private String salesInvoiceUid;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private Long cancelledBy;

    @Column(name = "cancel_reason", length = 255)
    private String cancelReason;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    protected RestaurantOrder() {}

    public RestaurantOrder(Long companyId, Long branchId, String orderNumber,
                           RestaurantOrderType orderType, Long restaurantTableId, Long openedBy) {
        if (orderType == RestaurantOrderType.DINE_IN && restaurantTableId == null) {
            throw new IllegalArgumentException("Choose a table for a dine-in order.");
        }
        this.companyId         = companyId;
        this.branchId          = branchId;
        this.orderNumber       = orderNumber;
        this.orderType         = orderType;
        this.restaurantTableId = orderType == RestaurantOrderType.DINE_IN ? restaurantTableId : null;
        this.openedBy          = openedBy;
        this.createdBy         = openedBy;
    }

    /** Moves a dine-in order to another table. A takeaway has no table to move. */
    public void moveToTable(Long tableId) {
        if (orderType != RestaurantOrderType.DINE_IN) {
            throw new IllegalArgumentException("A takeaway order is not at a table.");
        }
        this.restaurantTableId = tableId;
    }

    /**
     * Any change to the items reopens a billed order: the printed bill no longer matches it, so
     * it must be printed again before payment.
     */
    public void reopenAfterChange() {
        if (status == RestaurantOrderStatus.BILLED) {
            status = RestaurantOrderStatus.OPEN;
        }
    }

    /** Records a bill printing: the total shown and the reprint counter. */
    public void markBilled(BigDecimal grossTotal, Instant at) {
        this.status            = RestaurantOrderStatus.BILLED;
        this.billedTotalAmount = grossTotal;
        this.billedAt          = at;
        this.billPrintCount    = (billPrintCount == null ? 0 : billPrintCount) + 1;
    }

    public void markPaid(Long invoiceId, String invoiceUid, Instant at) {
        this.status          = RestaurantOrderStatus.PAID;
        this.salesInvoiceId  = invoiceId;
        this.salesInvoiceUid = invoiceUid;
        this.paidAt          = at;
    }

    public void cancel(String reason, Long by, Instant at) {
        this.status       = RestaurantOrderStatus.CANCELLED;
        this.cancelReason = reason;
        this.cancelledBy  = by;
        this.cancelledAt  = at;
    }
}
