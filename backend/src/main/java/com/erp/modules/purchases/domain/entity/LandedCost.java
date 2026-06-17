package com.erp.modules.purchases.domain.entity;

import com.erp.platform.common.money.CurrencyCode;
import com.erp.modules.purchases.domain.enums.LandedCostBasis;
import com.erp.modules.purchases.domain.enums.LandedCostStatus;
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
 * Landed Cost document header (ADR-0027 D-5).
 * On confirm: computes allocations, publishes LANDED_COST.ALLOCATED outbox event.
 * LC-#### assigned at create (D-10). Immutable once CONFIRMED (BR-PROC-12).
 */
@Getter
@Entity
@Table(name = "landed_costs")
public class LandedCost extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    @Column(name = "landed_cost_number", nullable = false, length = 30)
    private String landedCostNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private LandedCostStatus status = LandedCostStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "basis", nullable = false, length = 20)
    @Setter
    private LandedCostBasis basis = LandedCostBasis.BY_VALUE;

    @Column(name = "total_charge_amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal totalChargeAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private CurrencyCode currency;

    /** The capitalisation journal uid set by the stock handler (diagnostic). */
    @Column(name = "gl_entry_uid", length = 26)
    @Setter
    private String glEntryUid;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

    @Column(name = "confirmed_at")
    @Setter
    private Instant confirmedAt;

    @Column(name = "confirmed_by")
    @Setter
    private Long confirmedBy;

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

    protected LandedCost() {
        // JPA
    }

    public LandedCost(Long companyId, Long branchId, String landedCostNumber,
                      LandedCostBasis basis, String currency, String notes, Long createdBy) {
        this.companyId         = companyId;
        this.branchId          = branchId;
        this.landedCostNumber  = landedCostNumber;
        this.basis             = basis;
        this.currency          = CurrencyCode.ofNullable(currency);
        this.notes             = notes;
        this.createdBy         = createdBy;
    }
}
