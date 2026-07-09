package com.erp.modules.purchases.domain.entity;

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
 * Per-company PO approval threshold configuration (ADR-0027 D-6).
 * One row per company. Gate disabled by default (po_approval_enabled = false).
 */
@Getter
@Entity
@Table(name = "purchase_settings")
public class PurchaseSettings extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** NULL = any PO value triggers approval (when enabled). 0 = all POs. */
    @Column(name = "po_approval_threshold_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal poApprovalThresholdAmount;

    @Column(name = "po_approval_enabled", nullable = false)
    @Setter
    private boolean poApprovalEnabled = false;

    @Column(name = "currency", nullable = false, length = 3)
    @Setter
    private CurrencyCode currency = CurrencyCode.of("TZS");

    // -------------------------------------------------------------------------
    // P2 D7 — company-wide procurement policy defaults
    // -------------------------------------------------------------------------

    /** Soft-FK → payment_terms; default terms applied to new POs. No @ManyToOne (cross-module). */
    @Column(name = "default_payment_terms_id")
    @Setter
    private Long defaultPaymentTermsId;

    /** Soft-FK → stock_locations; default receiving location. No @ManyToOne (cross-module). */
    @Column(name = "default_location_id")
    @Setter
    private Long defaultLocationId;

    /** Default 3-way price-match tolerance percentage. */
    @Column(name = "match_tolerance_pct", precision = 9, scale = 4)
    @Setter
    private BigDecimal matchTolerancePct;

    /** Default absolute price-match tolerance. */
    @Column(name = "match_tolerance_abs", precision = 19, scale = 4)
    @Setter
    private BigDecimal matchToleranceAbs;

    /**
     * Goods-receipt over-receipt tolerance percent (e.g. 5.0000 = 5%). A goods receipt may exceed the
     * outstanding PO quantity by up to this much (Saidi #4). NULL / 0 = strict (no over-receipt).
     */
    @Column(name = "receipt_tolerance_pct", precision = 9, scale = 4)
    @Setter
    private BigDecimal receiptTolerancePct;

    /** Auto-close fully-received/billed POs. */
    @Column(name = "auto_close_enabled", nullable = false)
    @Setter
    private boolean autoCloseEnabled = false;

    /** Require purchase-requisition approval before RFQ/PO. */
    @Column(name = "requisition_approval_enabled", nullable = false)
    @Setter
    private boolean requisitionApprovalEnabled = false;

    /** PR value above which approval is required (when enabled). */
    @Column(name = "requisition_approval_threshold_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal requisitionApprovalThresholdAmount;

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

    protected PurchaseSettings() {
        // JPA
    }

    public PurchaseSettings(Long companyId, Long createdBy) {
        this.companyId = companyId;
        this.createdBy = createdBy;
    }
}
