package com.erp.modules.ap.domain.entity;

import com.erp.modules.ap.domain.enums.BillMatchStatus;
import com.erp.modules.ap.domain.enums.BillMatchType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-line 3-way match result (one row per bill line — ADR-0015 D-2c).
 * Records the price/qty variances, the tolerance applied, and who accepted an over-tolerance.
 * One current match per bill line (uq_bill_match_line); re-match updates the row.
 */
@Getter
@Entity
@Table(name = "bill_match")
public class BillMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "supplier_bill_id", nullable = false, updatable = false)
    private Long supplierBillId;

    @Column(name = "supplier_bill_line_id", nullable = false, updatable = false)
    private Long supplierBillLineId;

    /**
     * PO unit cost the line was compared against. NULL means the order line was never resolved —
     * i.e. the price leg of the control did not run. Settable because a re-match must overwrite
     * the previous run's figures; a stale number here is read as evidence a check happened.
     */
    @Column(name = "po_unit_cost_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal poUnitCostAmount;

    /** Received qty compared against. NULL means the receipt line was never resolved. */
    @Column(name = "gr_received_qty", precision = 19, scale = 6)
    @Setter
    private BigDecimal grReceivedQty;

    @Column(name = "billed_qty", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal billedQty;

    @Column(name = "price_variance_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal priceVarianceAmount = BigDecimal.ZERO;

    @Column(name = "price_variance_pct", nullable = false, precision = 9, scale = 4)
    @Setter
    private BigDecimal priceVariancePct = BigDecimal.ZERO;

    @Column(name = "qty_variance", nullable = false, precision = 19, scale = 6)
    @Setter
    private BigDecimal qtyVariance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false, length = 25)
    @Setter
    private BillMatchStatus matchStatus;

    /** P2 D7: 2-way (PO only) vs 3-way (PO + GR) match discipline. Defaults to THREE_WAY. */
    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", nullable = false, length = 10)
    @Setter
    private BillMatchType matchType = BillMatchType.THREE_WAY;

    /** P2 D7: free-text reason captured when a variance is accepted. */
    @Column(name = "variance_reason", length = 100)
    @Setter
    private String varianceReason;

    // -------------------------------------------------------------------------
    // P3 — audit-convenience scalar refs captured at match time (no FK — cross-module soft refs).
    // -------------------------------------------------------------------------

    /** P3: PO line uid the bill line matched. Nullable. */
    @Column(name = "po_line_uid", length = 26)
    @Setter
    private String poLineUid;

    /** P3: GR line uid drawn against. Nullable. */
    @Column(name = "gr_line_uid", length = 26)
    @Setter
    private String grLineUid;

    /** P3: GRNI clearing entry uid. Nullable. */
    @Column(name = "grni_entry_uid", length = 26)
    @Setter
    private String grniEntryUid;

    /** P3: currency snapshot for the variance amounts. Nullable. */
    @Column(name = "currency", length = 3)
    @Setter
    private String currency;

    @Column(name = "tolerance_pct", precision = 9, scale = 4)
    private BigDecimal tolerancePct;

    @Column(name = "tolerance_abs_amount", precision = 19, scale = 4)
    private BigDecimal toleranceAbsAmount;

    @Column(name = "accepted_by")
    @Setter
    private Long acceptedBy;

    @Column(name = "accepted_at")
    @Setter
    private Instant acceptedAt;

    @Column(name = "matched_at", nullable = false)
    @Setter
    private Instant matchedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected BillMatch() {
        // JPA
    }

    public BillMatch(Long companyId, Long supplierBillId, Long supplierBillLineId,
                     BigDecimal poUnitCostAmount, BigDecimal grReceivedQty, BigDecimal billedQty,
                     BigDecimal priceVarianceAmount, BigDecimal priceVariancePct, BigDecimal qtyVariance,
                     BillMatchStatus matchStatus,
                     BigDecimal tolerancePct, BigDecimal toleranceAbsAmount,
                     Long createdBy) {
        this.companyId            = companyId;
        this.supplierBillId       = supplierBillId;
        this.supplierBillLineId   = supplierBillLineId;
        this.poUnitCostAmount     = poUnitCostAmount;
        this.grReceivedQty        = grReceivedQty;
        this.billedQty            = billedQty;
        this.priceVarianceAmount  = priceVarianceAmount != null ? priceVarianceAmount : BigDecimal.ZERO;
        this.priceVariancePct     = priceVariancePct != null ? priceVariancePct : BigDecimal.ZERO;
        this.qtyVariance          = qtyVariance != null ? qtyVariance : BigDecimal.ZERO;
        this.matchStatus          = matchStatus;
        this.tolerancePct         = tolerancePct;
        this.toleranceAbsAmount   = toleranceAbsAmount;
        this.createdBy            = createdBy;
    }
}
