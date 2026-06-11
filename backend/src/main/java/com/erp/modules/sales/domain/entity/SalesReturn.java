package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.SalesReturnStatus;
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
 * Sales return / RMA header (ADR-0021 D-11, Stage 2).
 *
 * <p>Created in CONFIRMED status in v1. RET-#### allocated at create.
 * Emits DELIVERY.RETURNED outbox event (consumed by DeliveryReturnStockHandler).
 * Credit note raised synchronously via ArCreditNoteService with origin=RETURN.
 */
@Getter
@Entity
@Table(name = "sales_returns")
public class SalesReturn extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "return_number", nullable = false, length = 30)
    @Setter
    private String returnNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private SalesReturnStatus status = SalesReturnStatus.CONFIRMED;

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private Long deliveryId;

    @Column(name = "delivery_uid", nullable = false, length = 26, updatable = false)
    private String deliveryUid;

    @Column(name = "sales_order_uid", length = 26, updatable = false)
    private String salesOrderUid;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private Long customerId;

    @Column(name = "return_date", nullable = false, updatable = false)
    private LocalDate returnDate;

    /** UID of the AR credit note raised on confirm — set after credit note creation. */
    @Column(name = "credit_note_uid", length = 26)
    @Setter
    private String creditNoteUid;

    /** UID of the COGS-reversal GL entry (diagnostic only). */
    @Column(name = "cogs_reversal_gl_entry_uid", length = 26)
    @Setter
    private String cogsReversalGlEntryUid;

    @Column(name = "reason", length = 255)
    @Setter
    private String reason;

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

    protected SalesReturn() {
        // JPA
    }

    public SalesReturn(Long companyId, Long branchId,
                       Long deliveryId, String deliveryUid, String salesOrderUid,
                       Long customerId, LocalDate returnDate, String currency,
                       String reason, Long createdBy) {
        this.companyId     = companyId;
        this.branchId      = branchId;
        this.deliveryId    = deliveryId;
        this.deliveryUid   = deliveryUid;
        this.salesOrderUid = salesOrderUid;
        this.customerId    = customerId;
        this.returnDate    = returnDate;
        this.currency      = currency;
        this.reason        = reason;
        this.createdBy     = createdBy;
    }
}
