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
 * An item-level (partial) return of a finalised POS sale (ADR-0072, V117).
 *
 * <p>The original invoice stays {@code FINALISED}; the return is its own append-only document. The
 * refund is always CASH out of the till drawer of {@code posSessionId} (owner ruling 2026-11-21),
 * recorded as a REFUND {@code pos_session_payouts} row linked by {@code payoutId}. Amounts are the
 * sum of the return lines; {@code grossAmount} is the cash handed back.
 */
@Getter
@Entity
@Table(name = "pos_sale_returns")
public class PosSaleReturn extends UidEntity {

    @Column(name = "return_no", nullable = false, updatable = false, length = 30)
    private String returnNo;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "pos_session_id", nullable = false, updatable = false)
    private Long posSessionId;

    @Column(name = "sales_invoice_id", nullable = false, updatable = false)
    private Long salesInvoiceId;

    @Column(name = "reason", nullable = false, updatable = false, length = 255)
    private String reason;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmount;

    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountAmount;

    @Column(name = "vat_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal vatAmount;

    @Column(name = "gross_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal grossAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "refund_tender", nullable = false, length = 20)
    private String refundTender = "CASH";

    /** The REFUND payout that took the cash out of the drawer. */
    @Column(name = "payout_id")
    @Setter
    private Long payoutId;

    /** The approving supervisor's user id; null when the caller was the supervisor. */
    @Column(name = "authorised_by")
    private Long authorisedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected PosSaleReturn() {
        // JPA
    }

    public PosSaleReturn(String returnNo, Long companyId, Long branchId, Long posSessionId,
                         Long salesInvoiceId, String reason, BigDecimal netAmount,
                         BigDecimal discountAmount, BigDecimal vatAmount, BigDecimal grossAmount,
                         String currency, Long authorisedBy, Long createdBy) {
        this.returnNo       = returnNo;
        this.companyId      = companyId;
        this.branchId       = branchId;
        this.posSessionId   = posSessionId;
        this.salesInvoiceId = salesInvoiceId;
        this.reason         = reason;
        this.netAmount      = netAmount;
        this.discountAmount = discountAmount;
        this.vatAmount      = vatAmount;
        this.grossAmount    = grossAmount;
        this.currency       = currency;
        this.authorisedBy   = authorisedBy;
        this.createdBy      = createdBy;
    }
}
