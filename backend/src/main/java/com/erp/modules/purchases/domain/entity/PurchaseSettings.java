package com.erp.modules.purchases.domain.entity;

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
    private String currency = "TZS";

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
