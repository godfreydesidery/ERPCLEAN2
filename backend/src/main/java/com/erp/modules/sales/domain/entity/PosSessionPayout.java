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
 * A cash payout recorded during a POS session (ADR-0029 D-5): cash leaving the drawer as a REFUND,
 * a PAID_OUT drop, or a categorised till EXPENSE.
 *
 * <p>K8/V94 added the three columns that let a payout <em>be</em> an expense: {@code category} (the
 * bucket the operator picked), {@code gl_account_id} (the expense account it was posted to) and
 * {@code journal_entry_uid} (the GL journal it produced). All three are nullable — historical rows
 * predate the columns and were never posted, and REFUND rows raise no journal at all.
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

    /**
     * The expense bucket the till operator picked (V94, {@code VARCHAR(40)}). Mandatory on an
     * EXPENSE, null on REFUND/PAID_OUT — it is what the cross-session expense report groups by.
     */
    @Column(name = "category", length = 40)
    private String category;

    @Column(name = "reason", length = 255)
    @Setter
    private String reason;

    /**
     * The chart-of-accounts row this payout was debited to (V94, FK → {@code chart_of_accounts.id}).
     * Recorded so an accountant can see — and reclassify from — what a till expense actually hit,
     * rather than inferring it from the journal.
     */
    @Column(name = "gl_account_id")
    private Long glAccountId;

    /**
     * The GL journal raised for this payout (V94, ULID). <b>This is the idempotency key</b>: a
     * non-null value means "already posted", so a retry of the posting can never raise a second
     * journal for the same drawer cash. Null on a REFUND (its cash leg belongs to the sale-void
     * path) and on every row written before V94.
     */
    @Column(name = "journal_entry_uid", length = 26)
    private String journalEntryUid;

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

    /**
     * A categorised till EXPENSE. Only an expense carries a {@code category}, so this is a separate
     * factory rather than another constructor argument every REFUND/PAID_OUT call site would pass
     * null for — the type and the category cannot drift apart.
     */
    public static PosSessionPayout expense(Long companyId, Long branchId, Long posSessionId,
                                           BigDecimal amount, String category, String reason,
                                           Long createdBy) {
        PosSessionPayout payout = new PosSessionPayout(companyId, branchId, posSessionId,
                PosPayoutType.EXPENSE, amount, reason, createdBy);
        payout.category = category;
        return payout;
    }

    /**
     * True once this payout has a GL journal against it. Callers MUST check this before posting:
     * it is the guard that makes the posting idempotent, so replaying it cannot credit cash twice.
     */
    public boolean isPostedToGl() {
        return journalEntryUid != null;
    }

    /**
     * Records the GL posting this payout produced — the account it was debited to and the journal
     * that resulted. After this, {@link #isPostedToGl()} is true and the row is never posted again.
     */
    public void markPostedToGl(Long glAccountId, String journalEntryUid) {
        this.glAccountId     = glAccountId;
        this.journalEntryUid = journalEntryUid;
    }
}
