package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.AuthorisedReadRequest;
import com.erp.modules.sales.domain.dto.CloseSessionRequest;
import com.erp.modules.sales.domain.dto.OpenSessionRequest;
import com.erp.modules.sales.domain.dto.PosExpenseRequest;
import com.erp.modules.sales.domain.dto.PosPayoutDto;
import com.erp.modules.sales.domain.dto.PosPayoutRequest;
import com.erp.modules.sales.domain.dto.PosSessionDto;
import com.erp.modules.sales.domain.dto.ReconcileSessionRequest;
import com.erp.modules.sales.domain.dto.TillExpenseReportDto;
import com.erp.modules.sales.domain.dto.XReadDto;
import com.erp.modules.sales.domain.dto.ZReadDto;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * POS session lifecycle (ADR-0029 D-5): open → payout* → close → reconcile.
 */
public interface PosSessionService {

    PosSessionDto openSession(OpenSessionRequest request);

    PosSessionDto getSessionByUid(String uid);

    /** List sessions for a company; when {@code status} is non-null, only that status is returned. */
    Page<PosSessionDto> listSessions(Long companyId, PosSessionStatus status, Pageable pageable);

    /**
     * Record cash leaving the drawer on an OPEN session, and account for it (K8).
     *
     * <p>This used to write a row and an audit event and nothing else — no journal, no cash-book
     * entry. Because expected cash already nets payouts, a correctly-recorded payout produced a ZERO
     * variance, so the reconcile posting never caught it either: GL cash was overstated and the P&amp;L
     * understated by every till payout ever taken. A {@code PAID_OUT} now posts DR expense / CR cash
     * in the same transaction as the row, so the two can never disagree.
     *
     * <p>Handles REFUND and PAID_OUT only. An EXPENSE is refused here and goes through
     * {@link #recordExpense(String, PosExpenseRequest)} — it is a separately-permissioned capability
     * that must carry a category.
     *
     * @return the recorded payout, carrying the GL journal reference when one was raised
     */
    PosPayoutDto recordPayout(String sessionUid, PosPayoutRequest request);

    /**
     * Record a categorised business expense paid out of the drawer on an OPEN session (K8, V94).
     *
     * <p>Same money movement as a PAID_OUT and the same DR expense / CR cash posting, but it lands
     * as its own payout type carrying the operator's {@code category} and the expense account it hit
     * — so it prints as its own line on the X/Z-read and rolls up into the cross-session expense
     * report, instead of disappearing into the merged "Payouts" figure.
     *
     * @return the recorded expense, carrying its category, GL account and journal reference
     */
    PosPayoutDto recordExpense(String sessionUid, PosExpenseRequest request);

    /**
     * Record a till expense under a client-supplied idempotency key (V98).
     *
     * <p><b>The defect this closes.</b> Two byte-identical POSTs wrote TWO payouts and TWO journals —
     * 2,468 posted for one 1,234 expense (verified live: payout rows 17 and 18). The POS sale
     * endpoint has had an {@code Idempotency-Key} since V70; the expense endpoint never did.
     *
     * <p><b>Reserve-before-process</b>, exactly as {@code PosSaleService.processSale}: the marker is
     * claimed inside this transaction BEFORE the payout is written, so a concurrent duplicate blocks
     * on the winner's insert and then REPLAYS the original payout rather than writing a second one or
     * refusing a caller who did nothing wrong. {@code payout_uid} is stamped after the payout exists,
     * in the same transaction; a failed expense rolls the marker back, freeing the key for a clean
     * retry. Reuse the SAME key on every retry of one expense entry — a key regenerated per attempt
     * defeats the mechanism entirely.
     *
     * <p>A blank/absent key preserves the previous behaviour, including the short-window duplicate
     * guard that is the only protection such a caller has.
     *
     * @param idempotencyKey the client's {@code Idempotency-Key} (nullable/blank = no dedup)
     * @return the recorded expense, or the ORIGINAL one when this call is a replay
     */
    PosPayoutDto recordExpense(String sessionUid, String idempotencyKey, PosExpenseRequest request);

    /**
     * The individual payouts recorded on a session, oldest first (K8) — the detail behind the merged
     * "Payouts" line and the per-type subtotals on the X/Z-read. Every type, so a cashier can see
     * their own shift's refunds, drops and expenses together.
     */
    java.util.List<PosPayoutDto> listPayouts(String sessionUid);

    /**
     * The cross-session till-expense report for a company (K8) — EXPENSE payouts only, with a
     * category roll-up. This is the manager-level view a cashier cannot open
     * ({@code POS.EXPENSE.VIEW}).
     *
     * @param from inclusive start date; null defaults to 30 days before today (UTC)
     * @param to   inclusive end date; null defaults to today (UTC)
     */
    TillExpenseReportDto tillExpenseReport(Long companyId, LocalDate from, LocalDate to);

    /** Close the session — cashier declares counted cash. */
    PosSessionDto closeSession(String sessionUid, CloseSessionRequest request);

    /**
     * X-read: non-resetting summary for manager spot-checks. Valid on an OPEN session (mid-shift)
     * and on a CLOSED one (the shift has ended but is not yet reconciled — it must still be
     * re-examinable). A RECONCILED session is reported by {@link #zRead(String)} instead.
     */
    XReadDto xRead(String sessionUid);

    /**
     * X-read served either on the caller's own {@code POS.SESSION.VIEW} or on a manager's approval.
     *
     * <p><b>Why this exists.</b> A shop owner took the X/Z reads off the cashier role; because
     * permission-denied actions are hidden, the rows vanished from the till and the cashier could no
     * longer even ask. The owner wants the entries visible and a manager's username/password to open
     * them — the shop-floor pattern of "call the supervisor over", not a permanent grant.
     *
     * <p><b>What the approval is, and is not.</b> {@code authorisedByUid} is not a credential and
     * grants nothing on its own: no token is minted, issued, or stored anywhere. The uid names a
     * person, and this method independently re-resolves that person — real, active, not locked, NOT
     * the caller, and genuinely holding {@code POS.SESSION.RECONCILE} <em>in the company of the
     * LOADED session</em>, never a company the caller named. Because nothing is issued, the elevation
     * cannot outlive the request: the next report needs a fresh approval.
     *
     * <p>A caller who holds {@code POS.SESSION.VIEW} is served directly and the field is ignored.
     * Either way the print is audited. The state rules are unchanged — this is
     * {@link #xRead(String)} with an authorisation step in front of it, not a second report.
     */
    XReadDto xReadAuthorised(String sessionUid, AuthorisedReadRequest request);

    /** Reconcile a CLOSED session — post variance GL entry, mark RECONCILED, return Z-read. */
    ZReadDto reconcileSession(String sessionUid, ReconcileSessionRequest request);

    /**
     * Z-read for an already-RECONCILED session, recomputed from persisted data — read-only and
     * repeatable, so the end-of-shift report can be reprinted or re-opened long after the
     * reconcile call returned. Byte-identical to what {@link #reconcileSession} returned.
     */
    ZReadDto zRead(String sessionUid);

    /**
     * Z-read served either on the caller's own {@code POS.SESSION.VIEW} or on a manager's approval —
     * the Z-read half of {@link #xReadAuthorised(String, AuthorisedReadRequest)}, with the same
     * tokenless, re-verified-per-request authorisation and the same unchanged state rule (the session
     * must already be RECONCILED before a Z-read exists at all).
     */
    ZReadDto zReadAuthorised(String sessionUid, AuthorisedReadRequest request);
}
