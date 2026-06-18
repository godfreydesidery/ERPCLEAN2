package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.QuotationStatus;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
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
 * Quotation header (ADR-0021 D-3).
 *
 * <p>A non-binding priced offer to a customer. Lifecycle: DRAFT → SENT → ACCEPTED/EXPIRED/REJECTED.
 * QUOTE-#### allocated at SEND. Conversion to SO sets converted_order_uid.
 * Extends UidEntity (id + uid + @Version).
 */
@Getter
@Entity
@Table(name = "quotations")
public class Quotation extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "quote_number", length = 30)
    @Setter
    private String quoteNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private QuotationStatus status = QuotationStatus.DRAFT;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "agent_id")
    @Setter
    private Long agentId;

    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    @Column(name = "quote_date", nullable = false)
    private LocalDate quoteDate;

    @Column(name = "valid_until", nullable = false)
    @Setter
    private LocalDate validUntil;

    /** Customer's own purchase-order reference (P2-M3). Nullable. */
    @Column(name = "customer_po_number", length = 60)
    @Setter
    private String customerPoNumber;

    /** Plain revision counter (P2-M3). Nullable. */
    @Column(name = "revision_no")
    @Setter
    private Integer revisionNo;

    /** Win-probability percent, 0.00–100.00 (P2-M3). Nullable. */
    @Column(name = "probability", precision = 5, scale = 2)
    @Setter
    private BigDecimal probability;

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

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

    @Column(name = "sent_at")
    @Setter
    private Instant sentAt;

    @Column(name = "accepted_at")
    @Setter
    private Instant acceptedAt;

    @Column(name = "rejected_at")
    @Setter
    private Instant rejectedAt;

    @Column(name = "expired_at")
    @Setter
    private Instant expiredAt;

    @Column(name = "converted_order_uid", length = 26)
    @Setter
    private String convertedOrderUid;

    /** Back-link to the CRM opportunity that converted to this quote (ADR-0031 D-7, V52). Nullable. */
    @Column(name = "source_opportunity_uid", length = 26)
    @Setter
    private String sourceOpportunityUid;

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

    protected Quotation() {
        // JPA
    }

    public Quotation(Long companyId, Long branchId, Long customerId, Long agentId,
                     String currency, LocalDate quoteDate, LocalDate validUntil, Long createdBy) {
        this.companyId = companyId;
        this.branchId = branchId;
        this.customerId = customerId;
        this.agentId = agentId;
        this.currency = CurrencyCode.of(currency);
        this.quoteDate = quoteDate;
        this.validUntil = validUntil;
        this.createdBy = createdBy;
    }
}
