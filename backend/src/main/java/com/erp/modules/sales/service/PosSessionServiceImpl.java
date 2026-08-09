package com.erp.modules.sales.service;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.sales.domain.dto.CloseSessionRequest;
import com.erp.modules.sales.domain.dto.OpenSessionRequest;
import com.erp.modules.sales.domain.dto.PayoutSubtotalDto;
import com.erp.modules.sales.domain.dto.PosExpenseRequest;
import com.erp.modules.sales.domain.dto.PosPayoutDto;
import com.erp.modules.sales.domain.dto.PosPayoutRequest;
import com.erp.modules.sales.domain.dto.PosSessionDto;
import com.erp.modules.sales.domain.dto.ReconcileSessionRequest;
import com.erp.modules.sales.domain.dto.TenderSubtotalDto;
import com.erp.modules.sales.domain.dto.TillExpenseCategoryTotalDto;
import com.erp.modules.sales.domain.dto.TillExpenseReportDto;
import com.erp.modules.sales.domain.dto.XReadDto;
import com.erp.modules.sales.domain.dto.ZReadDto;
import com.erp.modules.sales.domain.entity.PosExpenseIdempotency;
import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.entity.PosSessionPayout;
import com.erp.modules.sales.domain.enums.PosPayoutType;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import com.erp.modules.sales.repository.PosExpenseIdempotencyRepository;
import com.erp.modules.sales.repository.PosSessionPayoutRepository;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.PosTillRepository;
import com.erp.modules.sales.repository.SalesInvoicePaymentRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PosSessionServiceImpl implements PosSessionService {

    private static final Logger log = LoggerFactory.getLogger(PosSessionServiceImpl.class);

    /** Shortest reason that can plausibly say what drawer cash was spent on (K8). */
    private static final int MIN_PAYOUT_REASON_LENGTH = 3;

    /** Shortest usable expense bucket ("PA" for parking is already thin, but it is a word). */
    private static final int MIN_EXPENSE_CATEGORY_LENGTH = 2;

    /** {@code pos_session_payouts.category} is VARCHAR(40) (V94) — refuse rather than silently cut. */
    private static final int MAX_EXPENSE_CATEGORY_LENGTH = 40;

    /** Default window of the cross-session expense report when the caller names no dates. */
    private static final int DEFAULT_EXPENSE_REPORT_DAYS = 30;

    /** Bucket for an expense row with no category — impossible through the API, visible if it happens. */
    private static final String UNCATEGORISED_EXPENSE = "Uncategorised";

    /** {@code pos_expense_idempotency.idem_key} is VARCHAR(80) (V98). */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 80;

    /**
     * How long an identical till expense is treated as a re-submission of the same payment rather
     * than a second, separate one (UAT finding C4).
     *
     * <p><b>Why a window and not a permanent rule.</b> Two 2,000 fares on the same shift are an
     * ordinary day in a shop, so "same session + same amount + same category + same note" can never
     * be permanently unique — it would refuse real cash movements. What is NOT ordinary is the same
     * expense arriving twice within seconds: that is a double-tapped button, a POS retry after a
     * lost response, or an offline queue flushing the same entry twice, and every one of those is a
     * single payment that must produce a single payout row and a single journal.
     *
     * <p><b>Why two minutes.</b> It has to cover the longest retry a client can plausibly make of
     * one user action — an HTTP timeout (30 s) plus a backed-off retry, or an operator giving up on
     * a spinner and pressing the button again — while staying far below any plausible gap between
     * two genuinely separate payments. Two minutes clears the first comfortably and is 30× shorter
     * than the "two identical fares an hour apart" case, which passes untouched.
     *
     * <p><b>Superseded, and now scoped to keyless callers only (V98).</b> The real fix shipped: a
     * client-supplied {@code Idempotency-Key} with a unique index
     * ({@code pos_expense_idempotency}), which closes the genuinely simultaneous pair this guard
     * never could — two transactions can both read before either commits, but only one can win the
     * insert. Where both mechanisms could speak, the key wins: a keyed retry is REPLAYED before this
     * guard is reached, and two DIFFERENT keys mean the client declared two separate payments, so
     * refusing the second on "same amount, category and note" would reject a real fare. The guard
     * therefore runs only when the request carries no key — an older POS build, for which it remains
     * the only protection against the double-tap it was written for (payout rows 17 and 18: same
     * session, 1,234 TZS, TRANSPORT, same note, 165 ms apart, two journals, 2,468 TZS in the GL for
     * 1,234 that left the drawer).
     */
    private static final Duration EXPENSE_DUPLICATE_WINDOW = Duration.ofMinutes(2);

    private final PosSessionRepository       sessions;
    private final PosTillRepository          tills;
    private final PosSessionPayoutRepository payouts;
    /** V98: the reserve-before-process dedup marker for till expenses. */
    private final PosExpenseIdempotencyRepository expenseIdempotency;
    private final SalesInvoiceRepository     invoices;
    private final SalesInvoicePaymentRepository tenderPayments;
    private final GLPostingSafeInvoker       glInvoker;
    private final GLConfigResolver           glConfig;
    /**
     * K8: posts a till payout in the CALLER's transaction, unlike {@link #glInvoker} whose
     * REQUIRES_NEW boundary exists for async outbox handlers. Recording drawer cash and accounting
     * for it must be one atomic act — a journal that commits while the payout row rolls back would
     * credit cash that never left the till.
     */
    private final GLPostingService           glPosting;
    private final CompanyRepository          companies;
    private final ScopeGuard                 scopeGuard;
    private final AuditService               audit;
    private final SalesDepthNumberGenerator  numberGen;
    /**
     * V97: provisions this company's till-expense account on demand. Called on the posting path so a
     * tenant that upgrades mid-shift needs no manual setup step before an expense can post.
     */
    private final TillExpenseGlSeeder        tillExpenseGl;

    public PosSessionServiceImpl(PosSessionRepository sessions,
                                  PosTillRepository tills,
                                  PosSessionPayoutRepository payouts,
                                  PosExpenseIdempotencyRepository expenseIdempotency,
                                  SalesInvoiceRepository invoices,
                                  SalesInvoicePaymentRepository tenderPayments,
                                  GLPostingSafeInvoker glInvoker,
                                  GLConfigResolver glConfig,
                                  GLPostingService glPosting,
                                  CompanyRepository companies,
                                  ScopeGuard scopeGuard,
                                  AuditService audit,
                                  SalesDepthNumberGenerator numberGen,
                                  TillExpenseGlSeeder tillExpenseGl) {
        this.sessions       = sessions;
        this.tills          = tills;
        this.payouts        = payouts;
        this.expenseIdempotency = expenseIdempotency;
        this.invoices       = invoices;
        this.tenderPayments = tenderPayments;
        this.glInvoker      = glInvoker;
        this.glConfig       = glConfig;
        this.glPosting      = glPosting;
        this.companies      = companies;
        this.scopeGuard     = scopeGuard;
        this.audit          = audit;
        this.numberGen      = numberGen;
        this.tillExpenseGl  = tillExpenseGl;
    }

    @Override
    public PosSessionDto openSession(OpenSessionRequest req) {
        var till = tills.findByUid(req.tillUid())
                .orElseThrow(() -> NotFoundException.of("PosTill", req.tillUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), till.getCompanyId());

        // Guard: reject if till is not ACTIVE (INACTIVE/ARCHIVED tills cannot open sessions).
        if (till.getStatus() != com.erp.platform.common.domain.MasterStatus.ACTIVE) {
            // req.tillUid() intentionally not surfaced (error-hygiene rule)
            throw new ConflictException(
                    "This till is " + till.getStatus()
                            + " and cannot open a session. Activate the till first.");
        }

        // Enforce at-most-one-open constraint (mirrors DB partial unique index)
        if (sessions.findByPosTillIdAndStatus(till.getId(), PosSessionStatus.OPEN).isPresent()) {
            throw new ConflictException("This till already has an OPEN session.");
        }

        var session = new PosSession(till.getCompanyId(), till.getBranchId(), till.getId(),
                actorId(), numberGen.nextPosSession(till.getCompanyId()),
                req.openingFloatAmount(), actorId());
        session = sessions.save(session);

        audit.record(AuditEvent.of(AuditActions.POS_SESSION_OPEN, "pos_sessions",
                session.getId(), session.getUid())
                .detail(Map.of("tillUid", req.tillUid())));
        return toDto(session);
    }

    @Override
    @Transactional(readOnly = true)
    public PosSessionDto getSessionByUid(String uid) {
        var session = requireSession(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        return toDto(session);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PosSessionDto> listSessions(Long companyId, PosSessionStatus status, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        var page = (status != null)
                ? sessions.findByCompanyIdAndStatus(companyId, status, pageable)
                : sessions.findByCompanyId(companyId, pageable);
        return page.map(this::toDto);
    }

    @Override
    public PosPayoutDto recordPayout(String sessionUid, PosPayoutRequest req) {
        var session = requireSession(sessionUid);
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        requireOpen(session);

        // An EXPENSE is a separately-permissioned capability (POS.EXPENSE.RECORD) and must carry a
        // category, so it cannot be filed through the generic payout form — otherwise anyone who can
        // open a session could book an uncategorised "expense" this endpoint's gate never checked.
        if (req.payoutType() == PosPayoutType.EXPENSE) {
            throw new ConflictException(
                    "Record a till expense on the expense form so it can be filed under a"
                    + " category and charged to the right account.");
        }

        var payout = new PosSessionPayout(session.getCompanyId(), session.getBranchId(),
                session.getId(), req.payoutType(), req.amount(),
                requireReason(req.reason()), actorId());
        return savePostAndAudit(session, payout);
    }

    @Override
    public PosPayoutDto recordExpense(String sessionUid, PosExpenseRequest req) {
        return recordExpense(sessionUid, null, req);
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Order of operations, and why.</b> Identity and authorisation first, then IDEMPOTENCY,
     * then the business guards — the same ordering {@code PosSaleServiceImpl.processSale} uses.
     * Whether this expense already posted must be answerable regardless of what the till has done
     * since: a replay arriving after the shift closed still gets its original payout back, because
     * refusing it would send the client round the same retry loop that created the double posting in
     * the first place.
     *
     * <p><b>What happens to the interim window guard.</b> It runs ONLY for a caller that sent no key.
     * The key wins wherever both could speak: a keyed retry is replayed above, before the guard is
     * reached, and two DIFFERENT keys mean the client deliberately declared two separate payments —
     * refusing the second on "same amount, category and note" would reject a legitimate second fare
     * that idempotency has already proved is not a duplicate. Keyless callers (an older POS build)
     * keep the guard, which is the only protection they have.
     */
    @Override
    public PosPayoutDto recordExpense(String sessionUid, String idempotencyKey,
                                       PosExpenseRequest req) {
        var session = requireSession(sessionUid);
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        Long companyId = session.getCompanyId();

        String key    = requireUsableKey(idempotencyKey);
        boolean keyed = key != null;

        if (keyed) {
            var claimed = expenseIdempotency.findByCompanyIdAndIdemKey(companyId, key);
            if (claimed.isPresent()) {
                return replayExpense(claimed.get());
            }
            // ON CONFLICT DO NOTHING returns 0 on a taken key, blocking first on an in-flight winner,
            // so two simultaneous duplicates can never both go on to write a payout.
            if (expenseIdempotency.tryReserve(companyId, session.getId(), key) == 0) {
                return replayExpense(expenseIdempotency
                        .findByCompanyIdAndIdemKey(companyId, key)
                        .orElseThrow(PosSessionServiceImpl::expenseInFlight));
            }
        }

        requireOpen(session);
        String category = requireCategory(req.category());
        String reason   = requireReason(req.reason());
        if (!keyed) {
            rejectRepeatedExpense(session, req.amount(), category, reason);
        }

        var payout = PosSessionPayout.expense(session.getCompanyId(), session.getBranchId(),
                session.getId(), req.amount(), category, reason, actorId());
        PosPayoutDto recorded = savePostAndAudit(session, payout);

        // Stamp in the SAME transaction as the payout, so the instant this commits a concurrent
        // duplicate sees the finished answer rather than a half-claimed key.
        if (keyed) {
            expenseIdempotency.stampPayoutUid(companyId, key, recorded.uid());
        }
        return recorded;
    }

    /**
     * Normalises the client's {@code Idempotency-Key}: {@code null} means "no dedup requested".
     *
     * <p>Length is checked against the column (V98, {@code VARCHAR(80)}) rather than left to fail as
     * a database error, so an over-long key is a sentence the operator can act on instead of a 500.
     */
    private static String requireUsableKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new ConflictException(
                    "The till sent an unusable reference for this expense. Record it again;"
                    + " if it keeps happening, report it to support.");
        }
        return key;
    }

    /**
     * Turns an existing marker into an answer.
     *
     * <p>Stamped marker → the ORIGINAL payout, whatever state the session is in now: that is the
     * whole point, and returning it costs nothing while re-posting would cost real money. Unstamped
     * → the winner is still inside its transaction, so this caller is told to wait rather than shown
     * a payout that may yet roll back.
     */
    private PosPayoutDto replayExpense(PosExpenseIdempotency marker) {
        if (marker.getPayoutUid() != null) {
            return payouts.findByUid(marker.getPayoutUid())
                    .map(PosSessionServiceImpl::toDto)
                    .orElseThrow(PosSessionServiceImpl::expenseInFlight);
        }
        throw expenseInFlight();
    }

    /** The one message for "an earlier attempt at this same expense has not finished yet". */
    private static ConflictException expenseInFlight() {
        return new ConflictException(
                "This expense is still being recorded. Wait a moment, then check the till's"
                + " payout list before entering it again — the cash has not been taken twice.");
    }

    @Override
    @Transactional(readOnly = true)
    public List<PosPayoutDto> listPayouts(String sessionUid) {
        var session = requireSession(sessionUid);
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        return payouts.findByPosSessionIdOrderByCreatedAtAscIdAsc(session.getId()).stream()
                .map(PosSessionServiceImpl::toDto)
                .toList();
    }

    /**
     * The cross-session till-expense report (K8) — what the shop's drawers actually paid out, by
     * category, over a date range.
     *
     * <p>Scoped to one company and asserted against the caller's active scope before a single row is
     * read, exactly as the session list is. Only EXPENSE rows are selected: folding refunds and
     * drawer drops into an "expense report" is the very confusion this feature removes.
     */
    @Override
    @Transactional(readOnly = true)
    public TillExpenseReportDto tillExpenseReport(Long companyId, LocalDate from, LocalDate to) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        LocalDate today    = LocalDate.now(ZoneOffset.UTC);
        LocalDate fromDate = (from != null) ? from : today.minusDays(DEFAULT_EXPENSE_REPORT_DAYS);
        LocalDate toDate   = (to != null) ? to : today;
        if (toDate.isBefore(fromDate)) {
            throw new ConflictException("The end date cannot be before the start date.");
        }

        // Half-open instant range: [fromDate 00:00, toDate+1 00:00) — an expense recorded at 23:59
        // on the closing day is inside the report, which a `<= toDate 00:00` bound would drop.
        List<PosPayoutDto> rows = payouts.findExpensesForCompanyBetween(
                        companyId, PosPayoutType.EXPENSE,
                        fromDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
                        toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant())
                .stream()
                .map(PosSessionServiceImpl::toDto)
                .toList();

        BigDecimal total = rows.stream()
                .map(PosPayoutDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TillExpenseReportDto(companyId, fromDate.toString(), toDate.toString(),
                total, rows.size(), rollUpByCategory(rows), rows);
    }

    @Override
    public PosSessionDto closeSession(String sessionUid, CloseSessionRequest req) {
        var session = requireSession(sessionUid);
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        requireOpen(session);

        // Compute expected cash: opening + cash-TENDER total − cash payouts (ADR-0029 D-3).
        // Only CASH tenders enter the physical drawer — card/mobile-money/cheque never do — so
        // the expected-cash formula must use the net CASH tender total, not invoice gross (busy-day
        // simulation bugfix: previously all tenders inflated "expected cash").
        // All payouts (REFUND, PAID_OUT) are outflows — subtract the total from expected.
        BigDecimal cashTenderTotal = computeCashTenderTotal(session);
        BigDecimal totalPayouts    = payouts.totalPayoutsForSession(session.getId());
        BigDecimal expected        = session.getOpeningFloatAmount()
                .add(cashTenderTotal).subtract(totalPayouts);

        session.setCountedCashAmount(req.countedCashAmount());
        session.setExpectedCashAmount(expected);
        session.setVarianceAmount(req.countedCashAmount().subtract(expected));
        session.setStatus(PosSessionStatus.CLOSED);
        session.setClosedAt(Instant.now());
        session.setNotes(req.notes());
        session.setUpdatedAt(Instant.now());
        session.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.POS_SESSION_CLOSE, "pos_sessions",
                session.getId(), session.getUid())
                .detail(Map.of("variance", session.getVarianceAmount().toPlainString())));
        return toDto(session);
    }

    @Override
    @Transactional(readOnly = true)
    public XReadDto xRead(String sessionUid) {
        var session = requireSession(sessionUid);
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        requireOpenOrClosed(session);

        BigDecimal cashTenderTotal  = computeCashTenderTotal(session);
        BigDecimal grossTurnover    = computeGrossTurnoverTotal(session);
        BigDecimal totalPayouts     = payouts.totalPayoutsForSession(session.getId());
        BigDecimal expected         = session.getOpeningFloatAmount()
                .add(cashTenderTotal).subtract(totalPayouts);
        long invoiceCount           = countPosInvoices(session);

        return new XReadDto(session.getUid(), session.getPosTillId(), session.getCashierId(),
                session.getOpenedAt().toString(), session.getOpeningFloatAmount(),
                grossTurnover, cashTenderTotal, totalPayouts, expected, invoiceCount,
                computeTenderSubtotals(session), computePayoutSubtotals(session));
    }

    @Override
    public ZReadDto reconcileSession(String sessionUid, ReconcileSessionRequest req) {
        var session = requireSession(sessionUid);
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        if (session.getStatus() != PosSessionStatus.CLOSED) {
            throw new ConflictException("Session must be CLOSED before reconciliation.");
        }

        // Post variance GL entry if non-zero (ADR-0029 D-4).
        // postVarianceGl propagates on missing GL config — the operator's command fails fast
        // (BR-INV-12 precedent: a human act, not an async handler, so fail-fast is correct).
        BigDecimal variance = session.getVarianceAmount();
        Long journalId = null;
        if (variance != null && variance.compareTo(BigDecimal.ZERO) != 0) {
            var journalEntry = postVarianceGl(session, variance);
            journalId = journalEntry != null ? journalEntry.id() : null;
        }

        session.setVarianceJournalId(journalId);
        if (req.notes() != null) {
            session.setNotes(req.notes());
        }
        session.setStatus(PosSessionStatus.RECONCILED);
        session.setReconciledAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.POS_SESSION_RECONCILE, "pos_sessions",
                session.getId(), session.getUid())
                .detail(Map.of("variance", variance == null ? "0" : variance.toPlainString())));

        // Built through the same helper the re-readable GET uses, so reprinting a Z-read can never
        // drift from the one the reconcile call returned.
        return buildZRead(session);
    }

    @Override
    @Transactional(readOnly = true)
    public ZReadDto zRead(String sessionUid) {
        var session = requireSession(sessionUid);
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        if (session.getStatus() != PosSessionStatus.RECONCILED) {
            throw new ConflictException(
                    "This session has not been reconciled yet, so there is no Z-read for it. "
                            + "Close and reconcile the session first; use the X-read until then.");
        }
        return buildZRead(session);
    }

    // ---- helpers ---------------------------------------------------------------

    /**
     * Single source of the Z-read record — used both by {@link #reconcileSession} (which returns it
     * once, from the mutated-and-about-to-flush session) and by {@link #zRead} (which recomputes it
     * from persisted data on demand). Every figure is either a persisted session column or an
     * aggregate over persisted rows, so the report is reproducible for the life of the session.
     */
    private ZReadDto buildZRead(PosSession session) {
        return new ZReadDto(session.getUid(), session.getPosTillId(), session.getCashierId(),
                session.getOpenedAt() == null ? null : session.getOpenedAt().toString(),
                session.getClosedAt() == null ? null : session.getClosedAt().toString(),
                session.getReconciledAt() == null ? null : session.getReconciledAt().toString(),
                session.getOpeningFloatAmount(),
                computeGrossTurnoverTotal(session),
                computeCashTenderTotal(session),
                payouts.totalPayoutsForSession(session.getId()),
                session.getExpectedCashAmount(),
                session.getCountedCashAmount(),
                session.getVarianceAmount(),
                session.getVarianceJournalId(),
                countPosInvoices(session),
                computeTenderSubtotals(session),
                computePayoutSubtotals(session));
    }

    /**
     * The one path that persists a payout: save the row, account for it, audit it. Both
     * {@link #recordPayout} and {@link #recordExpense} go through here so a REFUND, a PAID_OUT and an
     * EXPENSE cannot drift apart in how they are stored, posted or logged.
     */
    private PosPayoutDto savePostAndAudit(PosSession session, PosSessionPayout payout) {
        PosSessionPayout saved = payouts.save(payout);
        postPayoutGl(session, saved);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", saved.getPayoutType().name());
        detail.put("amount", saved.getAmount().toPlainString());
        detail.put("category", saved.getCategory() == null ? "" : saved.getCategory());
        detail.put("glEntryUid", saved.getJournalEntryUid() == null ? "" : saved.getJournalEntryUid());
        audit.record(AuditEvent.of(AuditActions.POS_SESSION_PAYOUT, "pos_session_payouts",
                saved.getId(), saved.getUid()).detail(detail));
        return toDto(saved);
    }

    /**
     * The reason is mandatory and trimmed here as well as bean-validated (K8), so a payload of
     * spaces cannot become an expense record nobody can explain later.
     */
    private static String requireReason(String raw) {
        String reason = raw == null ? "" : raw.trim();
        if (reason.length() < MIN_PAYOUT_REASON_LENGTH) {
            throw new ConflictException(
                    "Say what this cash is for before taking it out of the till"
                    + " (a few words is enough).");
        }
        return reason;
    }

    /**
     * Refuses an expense that repeats one already recorded on this session within
     * {@link #EXPENSE_DUPLICATE_WINDOW} (UAT finding C4).
     *
     * <p><b>The comparison, exactly.</b> Two expenses are "the same payment" when ALL of these hold:
     * <ul>
     *   <li>same POS session — a different till or a different shift is a different payment;</li>
     *   <li>payout type EXPENSE — a refund or a drawer drop is never merged with an expense;</li>
     *   <li>same amount, compared by numeric value ({@code compareTo}), so {@code 1234} and
     *       {@code 1234.00} are the same money;</li>
     *   <li>same category, trimmed and case-insensitive;</li>
     *   <li>same reason, trimmed and case-insensitive;</li>
     *   <li>recorded no earlier than {@code now − EXPENSE_DUPLICATE_WINDOW}.</li>
     * </ul>
     * Anything else — a different amount, bucket, note, session, or the same expense after the
     * window — is a distinct payment and is recorded normally. Wording the note differently is
     * therefore also the escape hatch for a real second payment inside the window, and it leaves the
     * expense report able to tell the two apart, which an identical pair never could.
     *
     * <p>Reads the session's own payouts (a shift's worth of rows, already loaded by the same query
     * the payout list uses) and filters here rather than adding a repository query: the set is small
     * and bounded by one session, and the comparison is a business rule, not a database predicate.
     */
    private void rejectRepeatedExpense(PosSession session, BigDecimal amount,
                                        String category, String reason) {
        Instant cutoff = Instant.now().minus(EXPENSE_DUPLICATE_WINDOW);
        boolean repeated = payouts.findByPosSessionIdOrderByCreatedAtAscIdAsc(session.getId())
                .stream()
                .anyMatch(p -> p.getPayoutType() == PosPayoutType.EXPENSE
                        && p.getCreatedAt() != null
                        && !p.getCreatedAt().isBefore(cutoff)
                        && p.getAmount() != null && p.getAmount().compareTo(amount) == 0
                        && sameText(p.getCategory(), category)
                        && sameText(p.getReason(), reason));
        if (repeated) {
            throw new ConflictException(
                    "That expense was already recorded a moment ago, so no extra cash was taken"
                    + " out of the till. If this is a second, separate payment, record it again in"
                    + " a couple of minutes or note what makes it different.");
        }
    }

    /** Trimmed, case-insensitive text match used by the repeated-expense comparison. */
    private static boolean sameText(String recorded, String candidate) {
        return recorded != null && recorded.trim().equalsIgnoreCase(candidate.trim());
    }

    /**
     * The category is what the expense report groups by, so a blank one would produce a bucket
     * nobody can act on. Trimmed and length-checked against the column (V94) rather than truncated —
     * silently cutting an operator's words is how a report stops meaning what it says.
     */
    private static String requireCategory(String raw) {
        String category = raw == null ? "" : raw.trim();
        if (category.length() < MIN_EXPENSE_CATEGORY_LENGTH
                || category.length() > MAX_EXPENSE_CATEGORY_LENGTH) {
            throw new ConflictException(
                    "Choose a short expense category (up to " + MAX_EXPENSE_CATEGORY_LENGTH
                    + " characters) — for example transport, water or casual labour.");
        }
        return category;
    }

    /**
     * Posts the GL entry for cash leaving the drawer — DR expense / CR cash (K8), and records the
     * account and journal on the payout row (V94).
     *
     * <p><b>The defect this closes.</b> A payout wrote a row and an audit event and nothing else.
     * Expected cash already nets payouts, so a correctly-recorded payout produces a ZERO variance and
     * the reconcile posting never sees it either. The result was that GL cash was overstated and the
     * P&amp;L understated by the value of every till payout ever taken.
     *
     * <p><b>Why it cannot double-count.</b> Against the reconcile posting: that entry books only the
     * variance (counted − expected), and expected already nets this payout, so the two are
     * orthogonal — a payout changes cash here and leaves the variance untouched. Against the sale
     * void: a {@code REFUND} payout is deliberately NOT posted. A POS refund is normally taken
     * through {@code reverseSale}, whose invoice void already reverses the cash leg; posting here as
     * well would credit cash twice for the same refund. Refund accounting belongs to the sale-void /
     * credit-note path and stays there.
     *
     * <p><b>Idempotency is now a persisted fact, not just atomicity.</b> The journal is still posted
     * in the same transaction that inserts the payout row (through {@link GLPostingService}, not the
     * REQUIRES_NEW invoker), so a failure takes the row down with it. On top of that, V94's
     * {@code journal_entry_uid} records the journal ON the row: a non-null value means "already
     * posted", and this method returns it untouched instead of raising a second entry. That is what
     * makes a replay — a retried command, a re-post sweep over existing rows — safe by construction
     * rather than by there happening to be only one caller.
     *
     * <p><b>The expense account (V97).</b> The debit goes to the account mapped to
     * {@link GlConfigKey#POS_TILL_EXPENSE}, and that account id is written to {@code gl_account_id}
     * so an accountant can see what a till payout actually hit and reclassify from the expense report
     * rather than reverse-engineer it from the journal. It used to go to
     * {@link GlConfigKey#POS_CASH_SHORT} — "Cash Short / Till Shortage" — which is a VARIANCE account
     * whose entire purpose is detecting drawer discrepancies: filling it with bus fares destroyed
     * that control signal AND misclassified operating expense by nature on the P&amp;L. There is
     * deliberately NO fallback to the shortage account; see
     * {@link #resolveTillExpenseAccount(Long)}. Per-category account mapping would need its own
     * configuration table and is deliberately not invented here.
     *
     * @return the journal's uid, or {@code null} when this payout type raises no entry
     */
    private String postPayoutGl(PosSession session, PosSessionPayout payout) {
        // The idempotency guard: a payout that already carries a journal is never posted again.
        if (payout.isPostedToGl()) {
            return payout.getJournalEntryUid();
        }
        if (!raisesGlEntry(payout.getPayoutType())) {
            return null;
        }
        Long companyId = session.getCompanyId();
        // Resolved OUTSIDE the try: its refusal is a specific, actionable sentence about the expense
        // account, and the catch below would flatten it into the generic one.
        ChartOfAccount expenseAcct = resolveTillExpenseAccount(companyId);
        try {
            ChartOfAccount cashAcct    = glConfig.resolve(companyId, GlConfigKey.CASH);
            // findScopedById, not findById: the id is the session's own company, never caller input,
            // and the named finder keeps this out of the confused-deputy guard's way.
            // Lambda, not a method reference: a method reference would need the iam Company ENTITY
            // imported into the sales module, which the module-boundary rule forbids.
            String currency = companies.findScopedById(companyId)
                    .map(c -> c.getBaseCurrency())
                    .orElse("TZS");
            BigDecimal amount = payout.getAmount();
            boolean isExpense = payout.getPayoutType() == PosPayoutType.EXPENSE;
            String  memo      = isExpense ? "Till expense: " + payout.getCategory() : "Till payout";
            var draft = new JournalEntryDraft(
                    companyId, session.getBranchId(),
                    LocalDate.now(ZoneOffset.UTC),
                    (isExpense ? "POS till expense " : "POS till payout ")
                            + session.getSessionNumber(),
                    JournalSourceType.CASH_DIRECT, payout.getUid(),
                    null, actorId(),
                    List.of(new LineDraft(expenseAcct.getId(), amount, BigDecimal.ZERO, currency,
                                    memo),
                            new LineDraft(cashAcct.getId(), BigDecimal.ZERO, amount, currency,
                                    "Cash out of till")));
            String journalUid = glPosting.post(draft).uid();
            // Persist the account and the journal on the row itself — this is what makes the next
            // attempt a no-op and what the expense report reads back.
            payout.markPostedToGl(expenseAcct.getId(), journalUid);
            return journalUid;
        } catch (RuntimeException ex) {
            // Fail-fast, and take the payout row down with it: cash that cannot be accounted for
            // must not be recorded as if it had been. Technical detail goes to the log only.
            log.warn("POS payout GL posting failed for company={} session={} — payout refused: {}",
                    companyId, session.getSessionNumber(), ex.toString());
            throw new ConflictException(
                    "This payout could not be recorded in the accounts, so no cash was taken out."
                    + " Ask your accountant to check the till expense and cash account setup,"
                    + " then try again.");
        }
    }

    /**
     * The account a till payout is debited to — the {@link GlConfigKey#POS_TILL_EXPENSE} mapping,
     * provisioned on demand (V97).
     *
     * <p><b>Self-healing.</b> {@link TillExpenseGlSeeder#ensureMapping(Long)} runs first and is a
     * single indexed read once the mapping exists. An upgraded tenant is normally already healed by
     * the seeder's start-up pass; this call is the backstop for a company created between two boots,
     * and it commits the mapping in a transaction of its own — so the mapping survives even if this
     * expense is refused a moment later, instead of rolling back with it and leaving the next
     * attempt in the same state. It never throws: when it cannot provision, the resolve below
     * produces the actionable refusal.
     *
     * <p><b>No fallback, on purpose.</b> If the mapping still cannot be resolved — the chart is
     * missing, or someone mapped the key to an account and then archived it — this refuses the
     * payout with a sentence the finance team can act on. Falling back to
     * {@link GlConfigKey#POS_CASH_SHORT} is exactly the bug being fixed: a refusal somebody has to
     * deal with beats a silent misposting nobody will ever notice.
     */
    private ChartOfAccount resolveTillExpenseAccount(Long companyId) {
        try {
            tillExpenseGl.ensureMapping(companyId);
            return glConfig.resolve(companyId, GlConfigKey.POS_TILL_EXPENSE);
        } catch (RuntimeException ex) {
            // Technical detail (which key, which account, why) goes to the log ONLY.
            log.warn("Till expense account unresolved for company={} — payout refused: {}",
                    companyId, ex.toString());
            throw new ConflictException(
                    "No account has been set up for till expenses, so this cash cannot be recorded"
                    + " and none was taken out. Ask your accountant to set the till expense account"
                    + " in the General Ledger settings, then try again.");
        }
    }

    /**
     * Which payout types move money the ledger has to hear about. PAID_OUT and EXPENSE both take
     * cash out of the business; a REFUND's cash leg is already reversed by the invoice void, so
     * posting it here would credit cash twice for one refund.
     */
    private static boolean raisesGlEntry(PosPayoutType type) {
        return type == PosPayoutType.PAID_OUT || type == PosPayoutType.EXPENSE;
    }

    /**
     * Groups the reported expenses into their buckets, biggest spend first — the line an owner
     * should question is the one at the top. Ties break on category name so two reads of the same
     * period are byte-identical. Computed in Java over the rows the report already carries, so the
     * roll-up and the detail can never disagree about what was included.
     */
    private static List<TillExpenseCategoryTotalDto> rollUpByCategory(List<PosPayoutDto> rows) {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, Long>       counts  = new LinkedHashMap<>();
        for (PosPayoutDto row : rows) {
            // Pre-V94 rows cannot be EXPENSE (the type did not exist), so a null category here would
            // mean a row written around the API — bucket it visibly rather than dropping it.
            String key = (row.category() == null || row.category().isBlank())
                    ? UNCATEGORISED_EXPENSE : row.category();
            amounts.merge(key, row.amount(), BigDecimal::add);
            counts.merge(key, 1L, Long::sum);
        }
        List<TillExpenseCategoryTotalDto> byCategory = new ArrayList<>(amounts.size());
        amounts.forEach((category, amount) ->
                byCategory.add(new TillExpenseCategoryTotalDto(category, amount, counts.get(category))));
        byCategory.sort((a, b) -> {
            int byAmountDescending = b.amount().compareTo(a.amount());
            return byAmountDescending != 0
                    ? byAmountDescending
                    : a.category().compareTo(b.category());
        });
        return List.copyOf(byCategory);
    }

    private static PosPayoutDto toDto(PosSessionPayout p) {
        return new PosPayoutDto(p.getId(), p.getUid(), p.getCompanyId(), p.getBranchId(),
                p.getPosSessionId(), p.getPayoutType(), p.getAmount(), p.getCategory(),
                p.getReason(),
                p.getCreatedAt() == null ? null : p.getCreatedAt().toString(),
                p.getCreatedBy(), p.getGlAccountId(), p.getJournalEntryUid());
    }

    /**
     * Posts the POS variance journal (ADR-0029 D-4).
     * <ul>
     *   <li>Over (variance > 0): DR CASH / CR POS_CASH_OVER 4900</li>
     *   <li>Short (variance &lt; 0): DR POS_CASH_SHORT 5170 / CR CASH</li>
     * </ul>
     * Currency comes from the company's base currency (ADR-0005; never hardcoded).
     * Posting date is the session's closed_at date so the entry falls in the correct GL period.
     * Exceptions propagate: a missing GL config must fail the operator's reconcile command
     * (ADR-0029 D-4 / BR-INV-12 precedent — not silently swallowed).
     */
    private com.erp.modules.gl.domain.dto.JournalEntryDto postVarianceGl(PosSession session,
                                                                           BigDecimal variance) {
        var cashAcct = glConfig.resolve(session.getCompanyId(), GlConfigKey.CASH);
        // Currency from company base currency (ADR-0005) — not hardcoded
        String currency = companies.findById(session.getCompanyId())
                .map(c -> c.getBaseCurrency())
                .orElse("TZS");
        // Posting date = session close date (not today) so GL period matches the session
        LocalDate postingDate = session.getClosedAt() != null
                ? session.getClosedAt().atZone(ZoneOffset.UTC).toLocalDate()
                : LocalDate.now();
        BigDecimal abs = variance.abs();
        LineDraft debitLine;
        LineDraft creditLine;
        if (variance.compareTo(BigDecimal.ZERO) > 0) {
            // over: DR Cash, CR POS_CASH_OVER
            var overAcct = glConfig.resolve(session.getCompanyId(), GlConfigKey.POS_CASH_OVER);
            debitLine  = new LineDraft(cashAcct.getId(), abs, BigDecimal.ZERO, currency, "POS cash over");
            creditLine = new LineDraft(overAcct.getId(), BigDecimal.ZERO, abs, currency, "POS cash over income");
        } else {
            // short: DR POS_CASH_SHORT, CR Cash
            var shortAcct = glConfig.resolve(session.getCompanyId(), GlConfigKey.POS_CASH_SHORT);
            debitLine  = new LineDraft(shortAcct.getId(), abs, BigDecimal.ZERO, currency, "POS cash short expense");
            creditLine = new LineDraft(cashAcct.getId(), BigDecimal.ZERO, abs, currency, "POS cash short");
        }
        var draft = new JournalEntryDraft(
                session.getCompanyId(), session.getBranchId(), postingDate,
                "POS session variance " + session.getSessionNumber(),
                JournalSourceType.POS_VARIANCE, session.getUid(),
                null, actorId(), List.of(debitLine, creditLine));
        return glInvoker.postInNewTx(draft);
    }

    /**
     * Net CASH tender retained in the till drawer — the ONLY figure that belongs in the
     * expected-cash arithmetic. See {@link SalesInvoicePaymentRepository#sumCashTenderByPosSession}.
     */
    private BigDecimal computeCashTenderTotal(PosSession session) {
        return tenderPayments.sumCashTenderByPosSession(session.getId());
    }

    /** Gross turnover across every tender type — reporting/display figure only. */
    private BigDecimal computeGrossTurnoverTotal(PosSession session) {
        return invoices.sumGrossByPosSession(session.getId());
    }

    private List<TenderSubtotalDto> computeTenderSubtotals(PosSession session) {
        return tenderPayments.sumByPosSessionGroupedByTender(session.getId());
    }

    /**
     * Per-type breakdown of the merged "Payouts" figure — refunds, drawer drops and till expenses
     * printed as three separate lines (K1's printed Z-read, K8's expense reporting).
     *
     * <p>Zero-filled to one row per {@link PosPayoutType} in enum order: the query only returns
     * types the session actually recorded, but a report whose rows appear and disappear between
     * shifts is unreadable, and a stable list keeps two reads of the same session identical. The
     * amounts always sum back to {@code totalPayoutsForSession}, since both aggregate the same rows.
     *
     * <p>EXPENSE is a real payout type since V94, so an expense no longer hides inside PAID_OUT: a
     * printed Z that lumped a refund together with a paid-out expense is the defect this fixes.
     * Adding a value to {@link PosPayoutType} adds its line here automatically.
     */
    private List<PayoutSubtotalDto> computePayoutSubtotals(PosSession session) {
        Map<PosPayoutType, PayoutSubtotalDto> recorded = payouts
                .payoutBreakdownForSession(session.getId()).stream()
                .collect(Collectors.toMap(PayoutSubtotalDto::payoutType, row -> row));
        return Arrays.stream(PosPayoutType.values())
                .map(type -> recorded.getOrDefault(type,
                        new PayoutSubtotalDto(type, BigDecimal.ZERO, 0L)))
                .toList();
    }

    private long countPosInvoices(PosSession session) {
        return invoices.countByPosSession(session.getId());
    }

    private void requireOpen(PosSession session) {
        if (session.getStatus() != PosSessionStatus.OPEN) {
            throw new ConflictException("Session " + session.getSessionNumber() + " is not OPEN.");
        }
    }

    /**
     * An X-read is a read, not a state change, so it stays valid after the shift ends: a manager
     * must be able to re-examine a CLOSED session that has not been reconciled yet. Once the
     * session is RECONCILED the Z-read is the authoritative end-of-shift report, so callers are
     * sent there rather than shown a second, subtly different summary.
     */
    private void requireOpenOrClosed(PosSession session) {
        // OPEN → CLOSED → RECONCILED: everything except the terminal state is X-readable.
        if (session.getStatus() == PosSessionStatus.RECONCILED) {
            throw new ConflictException("Session " + session.getSessionNumber()
                    + " has been reconciled — open its Z-read for the final figures.");
        }
    }

    private PosSession requireSession(String uid) {
        return sessions.findByUid(uid).orElseThrow(() -> NotFoundException.of("PosSession", uid));
    }

    private Long actorId() {
        var p = RequestContext.get();
        return (p != null) ? p.userId() : null;
    }

    private PosSessionDto toDto(PosSession s) {
        return new PosSessionDto(s.getId(), s.getUid(), s.getCompanyId(), s.getBranchId(),
                s.getPosTillId(), s.getCashierId(), s.getSessionNumber(), s.getStatus(),
                s.getOpenedAt() == null ? null : s.getOpenedAt().toString(),
                s.getClosedAt() == null ? null : s.getClosedAt().toString(),
                s.getReconciledAt() == null ? null : s.getReconciledAt().toString(),
                s.getOpeningFloatAmount(), s.getCountedCashAmount(),
                s.getExpectedCashAmount(), s.getVarianceAmount(),
                s.getVarianceJournalId(), s.getNotes());
    }
}
