package com.erp.modules.sales.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
