package com.erp.modules.sales.domain.entity;

import com.erp.modules.sales.domain.enums.PosPayoutType;
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
 * A cash-in or cash-out payout recorded during a POS session (ADR-0029 D-5).
 * CASH_IN adjusts the float upward; CASH_OUT removes cash for petty-cash/expenses.
 */
@Getter
@Entity
@Table(name = "pos_session_payouts")
public class PosSessionPayout extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private Long branchId;

    /** FK → pos_sessions.id */
    @Column(name = "pos_session_id", nullable = false, updatable = false)
    private Long posSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payout_type", nullable = false, length = 20)
    private PosPayoutType payoutType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "reason", length = 255)
    @Setter
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected PosSessionPayout() {}

    public PosSessionPayout(Long companyId, Long branchId, Long posSessionId,
                            PosPayoutType payoutType, BigDecimal amount,
                            String reason, Long createdBy) {
        this.companyId    = companyId;
        this.branchId     = branchId;
        this.posSessionId = posSessionId;
        this.payoutType   = payoutType;
        this.amount       = amount;
        this.reason       = reason;
        this.createdBy    = createdBy;
    }
}
