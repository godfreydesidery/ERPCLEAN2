package com.erp.api;

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
import com.erp.modules.sales.service.PosSessionService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * POS session lifecycle (ADR-0029 D-5): open, payout, expense, close, x-read, reconcile.
 *
 * <p>Session permissions are seeded in V43__pos.sql; the till-expense pair
 * ({@code POS.EXPENSE.RECORD} for the cashier, {@code POS.EXPENSE.VIEW} for the manager-only
 * cross-session report) is seeded in {@code R__seed_permissions.sql} (K8).
 */
@RestController
@RequestMapping("/api/v1/pos/sessions")
public class PosSessionController {

    private final PosSessionService sessionService;

    public PosSessionController(PosSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('POS.SESSION.OPEN')")
    public PosSessionDto open(@Valid @RequestBody OpenSessionRequest request) {
        return sessionService.openSession(request);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'possession','POS.SESSION.VIEW')")
    public PosSessionDto getByUid(@PathVariable String uid) {
        return sessionService.getSessionByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('POS.SESSION.VIEW')")
    public ApiResponse<List<PosSessionDto>> list(@RequestParam Long companyId,
                                                 @RequestParam(required = false) PosSessionStatus status,
                                                 Pageable pageable) {
        Page<PosSessionDto> page = sessionService.listSessions(companyId, status, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /**
     * Record a REFUND or PAID_OUT leaving the drawer (K8).
     *
     * <p>A {@code PAID_OUT} now posts DR expense / CR cash in the same transaction, so the drawer and
     * the ledger can never disagree, and {@code reason} is required — cash with no stated purpose is
     * not an expense anyone can account for. Returns the recorded payout, including the GL journal
     * reference when one was raised.
     *
     * <p>Gated on {@code POS.SESSION.OPEN}, unchanged. A payout of type EXPENSE is refused here and
     * belongs to {@link #recordExpense}: it is a separately-permissioned capability, so accepting it
     * on this gate would let anyone who can open a session book an uncategorised expense.
     */
    @PostMapping("/uid/{uid}/payouts")
    @PreAuthorize("@perm.scoped(#uid,'possession','POS.SESSION.OPEN')")
    public PosPayoutDto recordPayout(@PathVariable String uid,
                                      @Valid @RequestBody PosPayoutRequest request) {
        return sessionService.recordPayout(uid, request);
    }

    /**
     * Record a categorised business expense paid out of the drawer (K8, V94).
     *
     * <p>Gated on {@code POS.EXPENSE.RECORD}, which CASHIER holds — the cashier is the one who hands
     * the cash over, so they are the one who files the expense. Same DR expense / CR cash posting as
     * a paid-out, but the row keeps the {@code category}, the account it hit and the journal it
     * raised, so it prints as its own X/Z-read line and rolls up into the expense report.
     *
     * <p><b>Repeat submissions replay the original (V98).</b> Supply an {@code Idempotency-Key}
     * header and reuse the SAME value on every retry of one expense entry: the first call records
     * the payout and stamps the key, and every later call under that key returns THAT payout (200)
     * instead of taking the cash out of the ledger a second time. Two identical POSTs without a key
     * used to write two payouts and two journals — 2,468 posted for a single 1,234 expense.
     *
     * <p>Omitting the header preserves the previous behaviour, including the short-window duplicate
     * guard (same session, amount, category and note) that is a keyless caller's only protection;
     * see {@code PosSessionServiceImpl.EXPENSE_DUPLICATE_WINDOW}.
     */
    @PostMapping("/uid/{uid}/expenses")
    @PreAuthorize("@perm.scoped(#uid,'possession','POS.EXPENSE.RECORD')")
    public PosPayoutDto recordExpense(
            @PathVariable String uid,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PosExpenseRequest request) {
        return sessionService.recordExpense(uid, idempotencyKey, request);
    }

    /**
     * The individual payouts recorded on a session, oldest first (K8) — the detail behind the merged
     * "Payouts" total and the per-type subtotals the X/Z-read prints. The repository previously
     * offered only a SUM, so a shift's drawer-out cash could not be listed or questioned at all.
     *
     * <p>Session-scoped and every payout type, so a cashier can still account for their own shift on
     * {@code POS.SESSION.VIEW}. The cross-session picture is {@link #tillExpenses} and needs the
     * manager-only {@code POS.EXPENSE.VIEW}.
     */
    @GetMapping("/uid/{uid}/payouts")
    @PreAuthorize("@perm.scoped(#uid,'possession','POS.SESSION.VIEW')")
    public List<PosPayoutDto> listPayouts(@PathVariable String uid) {
        return sessionService.listPayouts(uid);
    }

    /**
     * The cross-session till-expense report (K8) — every EXPENSE the company's tills paid out over a
     * date range, with a category roll-up.
     *
     * <p>Gated on {@code POS.EXPENSE.VIEW}, held by SALES_MANAGER and BRANCH_MANAGER and deliberately
     * NOT by CASHIER: a cashier records the expenses on their own till but does not get to read what
     * every till in the company spent. Dates are optional — the default window is the last 30 days.
     */
    @GetMapping("/expenses")
    @PreAuthorize("@perm.has('POS.EXPENSE.VIEW')")
    public TillExpenseReportDto tillExpenses(
            @RequestParam Long companyId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return sessionService.tillExpenseReport(companyId, from, to);
    }

    @PostMapping("/uid/{uid}/close")
    @PreAuthorize("@perm.scoped(#uid,'possession','POS.SESSION.CLOSE')")
    public PosSessionDto close(@PathVariable String uid,
                                @Valid @RequestBody CloseSessionRequest request) {
        return sessionService.closeSession(uid, request);
    }

    /** X-read: non-resetting summary, valid while the session is OPEN or CLOSED. */
    @GetMapping("/uid/{uid}/x-read")
    @PreAuthorize("@perm.scoped(#uid,'possession','POS.SESSION.VIEW')")
    public XReadDto xRead(@PathVariable String uid) {
        return sessionService.xRead(uid);
    }

    @PostMapping("/uid/{uid}/reconcile")
    @PreAuthorize("@perm.scoped(#uid,'possession','POS.SESSION.RECONCILE')")
    public ZReadDto reconcile(@PathVariable String uid,
                               @RequestBody ReconcileSessionRequest request) {
        return sessionService.reconcileSession(uid, request);
    }

    /**
     * Re-readable Z-read for an already-RECONCILED session, recomputed from persisted data.
     *
     * <p>Reconciling is one-shot — it flips the session to RECONCILED and refuses to run twice — so
     * without this the end-of-shift report existed only as that one response body and was lost the
     * moment the dialog closed. This returns the identical {@link ZReadDto} as often as it is
     * asked, so the report can be reprinted or re-opened. Read-only: it posts nothing and changes
     * no state, hence the VIEW gate rather than RECONCILE.
     */
    @GetMapping("/uid/{uid}/z-read")
    @PreAuthorize("@perm.scoped(#uid,'possession','POS.SESSION.VIEW')")
    public ZReadDto zRead(@PathVariable String uid) {
        return sessionService.zRead(uid);
    }
}
