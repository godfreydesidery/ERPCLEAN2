package com.erp.modules.sales.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * Till-expense idempotency marker (V98) — the till-expense twin of {@link PosSaleIdempotency}.
 *
 * <p>One row per {@code (company_id, idem_key)} where {@code idem_key} is the client's
 * {@code Idempotency-Key} header; the unique constraint {@code uq_pos_expense_idem} is the dedup
 * key. Rows are RESERVED (insert) BEFORE the payout is written and stamped with {@code payout_uid}
 * afterwards, all inside the expense's own transaction — so a retry replays the original payout
 * instead of taking a second lot of cash out of the ledger, and a failed expense rolls the marker
 * back so the key is free for a clean retry.
 *
 * <p>{@code pos_session_id} is carried (the sale marker has no equivalent) because a till expense is
 * only ever meaningful inside a session: it makes retention purging and per-shift diagnostics
 * possible without joining through the payout.
 *
 * <p>Read-only from JPA's side; rows are written via a native {@code INSERT ... ON CONFLICT DO
 * NOTHING} reserve plus a JPQL stamp (see the repository).
 */
@Getter
@Entity
@Table(name = "pos_expense_idempotency")
public class PosExpenseIdempotency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "pos_session_id", nullable = false)
    private Long posSessionId;

    @Column(name = "idem_key", nullable = false, length = 80)
    private String idemKey;

    /** The recorded payout's uid; NULL only in the brief window before the winner stamps it. */
    @Column(name = "payout_uid", length = 26)
    private String payoutUid;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected PosExpenseIdempotency() {
        // JPA
    }
}
