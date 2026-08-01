package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.BelowCostAction;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
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
 * Per-company SO approval threshold configuration (deferred item D-4, extends the engine-derived
 * sales-order approval flow shipped in PR #189). One row per company. Gate disabled by default
 * (so_approval_enabled = false). Mirrors {@code PurchaseSettings}.
 */
@Getter
@Entity
@Table(name = "sales_settings")
public class SalesSettings extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** NULL = any SO value triggers approval (when enabled). 0 = all SOs. */
    @Column(name = "so_approval_threshold_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal soApprovalThresholdAmount;

    @Column(name = "so_approval_enabled", nullable = false)
    @Setter
    private boolean soApprovalEnabled = false;

    /**
     * Configurable "block negative stock on sale" (owner decision 2026-07-05, V87). {@code false}
     * (the default) = a sale that would drive on-hand negative is BLOCKED; {@code true} = backorder
     * is allowed. Enforced synchronously by {@code NegativeStockGuard} at every sale-issue path
     * (DIRECT/POS invoice finalise, SO delivery create) — NOT by the async stock-module consumers.
     */
    @Column(name = "allow_negative_stock", nullable = false)
    @Setter
    private boolean allowNegativeStock = false;

    /**
     * Configurable "sale at or below cost" policy (owner decision 2026-08-01, V93).
     * {@link BelowCostAction#OFF} (the default) = no check, the pre-V93 behaviour. Enforced
     * synchronously by {@code BelowCostGuard} at sales-invoice finalise (which is also the POS
     * path) against the product's moving-average cost.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "below_cost_action", nullable = false, length = 16)
    @Setter
    private BelowCostAction belowCostAction = BelowCostAction.OFF;

    @Column(name = "currency", nullable = false, length = 3)
    @Setter
    private CurrencyCode currency = CurrencyCode.of("TZS");

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

    protected SalesSettings() {
        // JPA
    }

    public SalesSettings(Long companyId, Long createdBy) {
        this.companyId = companyId;
        this.createdBy = createdBy;
    }
}
