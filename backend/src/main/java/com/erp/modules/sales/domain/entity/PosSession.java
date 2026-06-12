package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.PosSessionStatus;
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
 * A cashier session on a POS till (ADR-0029 D-5).
 * Lifecycle: OPEN → CLOSED → RECONCILED.
 * All sales during the session carry pos_session_id back to this row.
 */
@Getter
@Entity
@Table(name = "pos_sessions")
public class PosSession extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** FK → pos_tills.id */
    @Column(name = "pos_till_id", nullable = false, updatable = false)
    private Long posTillId;

    /** FK → app_users.id; the cashier who opened the session. */
    @Column(name = "cashier_id", nullable = false, updatable = false)
    private Long cashierId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Setter
    private PosSessionStatus status = PosSessionStatus.OPEN;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "closed_at")
    @Setter
    private Instant closedAt;

    @Column(name = "reconciled_at")
    @Setter
    private Instant reconciledAt;

    /** Expected float at open (opening cash balance). */
    @Column(name = "opening_float_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal openingFloatAmount;

    /** Cashier-counted cash at close; null until session is closed. */
    @Column(name = "counted_cash_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal countedCashAmount;

    /** System-computed expected cash = opening + cash sales + CASH_IN payouts - CASH_OUT payouts. */
    @Column(name = "expected_cash_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal expectedCashAmount;

    /** Variance = counted - expected (positive = over, negative = short). */
    @Column(name = "variance_amount", precision = 19, scale = 4)
    @Setter
    private BigDecimal varianceAmount;

    /** FK → journal_entries.id; the variance posting raised at reconciliation. Nullable. */
    @Column(name = "variance_journal_id")
    @Setter
    private Long varianceJournalId;

    @Column(name = "notes", length = 500)
    @Setter
    private String notes;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    protected PosSession() {}

    public PosSession(Long companyId, Long branchId, Long posTillId, Long cashierId,
                      BigDecimal openingFloatAmount, Long createdBy) {
        this.companyId           = companyId;
        this.branchId            = branchId;
        this.posTillId           = posTillId;
        this.cashierId           = cashierId;
        this.openingFloatAmount  = openingFloatAmount;
        this.createdBy           = createdBy;
    }
}
