package com.erp.modules.tax.domain.entity;

import com.erp.modules.tax.domain.enums.VatReturnStatus;
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
 * Monthly VAT return (ADR-0017 D-2a). One per company per calendar month.
 * Extends UidEntity (id + uid + @Version).
 */
@Getter
@Entity
@Table(name = "vat_returns")
public class VatReturn extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "return_number", nullable = false, length = 30)
    private String returnNumber;

    @Column(name = "period_year", nullable = false)
    private short periodYear;

    @Column(name = "period_month", nullable = false)
    private short periodMonth;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "due_date", nullable = false)
    @Setter
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Setter
    private VatReturnStatus status = VatReturnStatus.DRAFT;

    @Column(name = "output_vat", nullable = false)
    @Setter
    private BigDecimal outputVat = BigDecimal.ZERO;

    @Column(name = "input_vat", nullable = false)
    @Setter
    private BigDecimal inputVat = BigDecimal.ZERO;

    @Column(name = "adjustments_total", nullable = false)
    @Setter
    private BigDecimal adjustmentsTotal = BigDecimal.ZERO;

    @Column(name = "opening_credit", nullable = false)
    @Setter
    private BigDecimal openingCredit = BigDecimal.ZERO;

    @Column(name = "net_vat", nullable = false)
    @Setter
    private BigDecimal netVat = BigDecimal.ZERO;

    @Column(name = "closing_credit", nullable = false)
    @Setter
    private BigDecimal closingCredit = BigDecimal.ZERO;

    @Column(name = "prior_return_id")
    @Setter
    private Long priorReturnId;

    @Column(name = "filing_reference", length = 80)
    @Setter
    private String filingReference;

    @Column(name = "filing_date")
    @Setter
    private LocalDate filingDate;

    @Column(name = "posted_journal_uid", length = 26)
    @Setter
    private String postedJournalUid;

    @Column(name = "filed_at")
    @Setter
    private Instant filedAt;

    @Column(name = "filed_by")
    @Setter
    private Long filedBy;

    // -------------------------------------------------------------------------
    // P2-M1 — payment tracking + turnover figures
    // -------------------------------------------------------------------------

    /** Timestamp when the VAT liability was remitted to the tax authority. */
    @Column(name = "paid_at")
    @Setter
    private Instant paidAt;

    /** Amount actually paid to the tax authority. */
    @Column(name = "paid_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal paidAmount;

    /** Authority/bank payment reference for the remittance. */
    @Column(name = "payment_reference", length = 80)
    @Setter
    private String paymentReference;

    /** Total (inc. VAT) sales turnover for the period. */
    @Column(name = "sales_turnover", precision = 19, scale = 4)
    @Setter
    private BigDecimal salesTurnover;

    /** Total (inc. VAT) purchases turnover for the period. */
    @Column(name = "purchases_turnover", precision = 19, scale = 4)
    @Setter
    private BigDecimal purchasesTurnover;

    /** Zero-rated sales base for the period. */
    @Column(name = "zero_rated_sales", precision = 19, scale = 4)
    @Setter
    private BigDecimal zeroRatedSales;

    /** Exempt sales base for the period. */
    @Column(name = "exempt_sales", precision = 19, scale = 4)
    @Setter
    private BigDecimal exemptSales;

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

    protected VatReturn() {
        // JPA
    }

    public VatReturn(Long companyId, String returnNumber,
                     short periodYear, short periodMonth,
                     LocalDate periodStart, LocalDate periodEnd,
                     LocalDate dueDate, Long priorReturnId,
                     BigDecimal openingCredit, Long createdBy) {
        this.companyId     = companyId;
        this.returnNumber  = returnNumber;
        this.periodYear    = periodYear;
        this.periodMonth   = periodMonth;
        this.periodStart   = periodStart;
        this.periodEnd     = periodEnd;
        this.dueDate       = dueDate;
        this.priorReturnId = priorReturnId;
        this.openingCredit = openingCredit != null ? openingCredit : BigDecimal.ZERO;
        this.createdBy     = createdBy;
    }
}
