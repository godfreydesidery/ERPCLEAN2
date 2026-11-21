package com.erp.modules.sales.service;

import com.erp.modules.ar.service.ArBalanceService;
import com.erp.modules.iam.service.StepUpAuthService;
import com.erp.modules.iam.service.UserLookupService;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.sales.domain.dto.PosReturnedLineTotalsDto;
import com.erp.modules.sales.domain.dto.PosSaleReturnDto;
import com.erp.modules.sales.domain.dto.PosSaleReturnRequest;
import com.erp.modules.sales.domain.dto.PosSaleReturnedPayload;
import com.erp.modules.sales.domain.dto.PosSaleReturnsDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceLineDto;
import com.erp.modules.sales.domain.entity.PosSaleReturn;
import com.erp.modules.sales.domain.entity.PosSaleReturnLine;
import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.entity.PosSessionPayout;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.entity.SalesInvoiceLine;
import com.erp.modules.sales.domain.enums.DocumentOrigin;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.repository.PosSaleIdempotencyRepository;
import com.erp.modules.sales.repository.PosSaleReturnLineRepository;
import com.erp.modules.sales.repository.PosSaleReturnRepository;
import com.erp.modules.sales.repository.PosSessionPayoutRepository;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.SalesInvoiceLineRepository;
import com.erp.modules.sales.repository.SalesInvoicePaymentRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyMinorUnits;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * POS item returns (ADR-0072). See {@link PosSaleReturnService} for the contract.
 *
 * <h2>What one return writes, in one transaction</h2>
 * <ol>
 *   <li>the {@code pos_sale_returns} header and its lines (pro-rated by {@link PosReturnAmounts});</li>
 *   <li>a REFUND {@code pos_session_payouts} row — so the drawer's expected cash drops. It raises no
 *       journal itself (a REFUND payout never does: the cash leg belongs to the reversal journal);</li>
 *   <li>one {@code POS.SALE.RETURNED} outbox event — stock puts the goods back, GL posts the
 *       {@code SALES_REVERSAL} journal (Dr Revenue, Dr VAT, Cr Cash) under the RETURN's uid, and the
 *       cash book mirrors that journal's cash leg exactly once;</li>
 *   <li>the audit row.</li>
 * </ol>
 *
 * <h2>Concurrency</h2>
 * The sale row is taken with {@code PESSIMISTIC_FORCE_INCREMENT}: two returns of one sale queue
 * behind each other (so the second sees the first's lines and cannot over-return), and a whole-sale
 * reverse that loaded the invoice earlier fails its version check instead of refunding twice.
 *
 * <h2>Idempotency</h2>
 * The same mechanism as {@code processSale}: the {@code Idempotency-Key} is reserved in
 * {@code pos_sale_idempotency} (key stored as {@code RET:<key>} so it can never collide with a sale's
 * key) and stamped, in this transaction, with the return's uid. A retry gets the original return back.
 */
@Service
@Transactional
public class PosSaleReturnServiceImpl implements PosSaleReturnService {

    /** Marker-key prefix; keeps return keys in their own namespace of {@code pos_sale_idempotency}. */
    static final String KEY_PREFIX = "RET:";
    /** {@code idem_key} is VARCHAR(80). */
    private static final int MAX_KEY_LENGTH = 80 - KEY_PREFIX.length();

    private final ProductRepository products;
    private final SalesInvoiceRepository invoices;
    private final SalesInvoiceLineRepository invoiceLines;
    private final SalesInvoicePaymentRepository tenders;
    private final PosSessionRepository sessions;
    private final PosSessionPayoutRepository payouts;
    private final PosSaleReturnRepository returns;
    private final PosSaleReturnLineRepository returnLines;
    private final PosSaleIdempotencyRepository idempotency;
    private final OrderToCashNumberGenerator numbers;
    private final ArBalanceService arBalance;
    private final StepUpAuthService stepUpAuth;
    private final UserLookupService users;
    private final PermissionResolver permissions;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final CurrencyMinorUnits minorUnits;

    public PosSaleReturnServiceImpl(ProductRepository products,
                                    SalesInvoiceRepository invoices,
                                    SalesInvoiceLineRepository invoiceLines,
                                    SalesInvoicePaymentRepository tenders,
                                    PosSessionRepository sessions,
                                    PosSessionPayoutRepository payouts,
                                    PosSaleReturnRepository returns,
                                    PosSaleReturnLineRepository returnLines,
                                    PosSaleIdempotencyRepository idempotency,
                                    OrderToCashNumberGenerator numbers,
                                    ArBalanceService arBalance,
                                    StepUpAuthService stepUpAuth,
                                    UserLookupService users,
                                    PermissionResolver permissions,
                                    ScopeGuard scopeGuard,
                                    AuditService audit,
                                    OutboxPublisher outbox,
                                    CurrencyMinorUnits minorUnits) {
        this.products     = products;
        this.invoices     = invoices;
        this.invoiceLines = invoiceLines;
        this.tenders      = tenders;
        this.sessions     = sessions;
        this.payouts      = payouts;
        this.returns      = returns;
        this.returnLines  = returnLines;
        this.idempotency  = idempotency;
        this.numbers      = numbers;
        this.arBalance    = arBalance;
        this.stepUpAuth   = stepUpAuth;
        this.users        = users;
        this.permissions  = permissions;
        this.scopeGuard   = scopeGuard;
        this.audit        = audit;
        this.outbox       = outbox;
        this.minorUnits   = minorUnits;
    }

    // -------------------------------------------------------------------------
    // Return
    // -------------------------------------------------------------------------

    @Override
    public PosSaleReturnDto returnItems(String invoiceUid, String idempotencyKey,
                                        PosSaleReturnRequest request) {
        SalesInvoice found = invoices.findByUid(invoiceUid)
                .orElseThrow(() -> NotFoundException.of("SalesInvoice", invoiceUid));
        scopeGuard.assertCanActIn(RequestContext.get(), found.getCompanyId());
        Long companyId = found.getCompanyId();

        // IDEMPOTENCY FIRST: whether this return already posted must stay answerable whatever the
        // till or the session has done since (the same ordering processSale uses, K11).
        String key = markerKey(idempotencyKey);
        if (key != null) {
            var claimed = idempotency.findByCompanyIdAndIdemKey(companyId, key);
            if (claimed.isPresent()) {
                return replay(found, claimed.get().getInvoiceUid());
            }
            // ON CONFLICT DO NOTHING returns 0 on a taken key, blocking first on an in-flight winner.
            if (idempotency.tryReserve(companyId, key) == 0) {
                var marker = idempotency.findByCompanyIdAndIdemKey(companyId, key)
                        .orElseThrow(PosSaleReturnServiceImpl::inFlight);
                return replay(found, marker.getInvoiceUid());
            }
        }

        // Serialise returns (and reverses) of this sale from here on.
        SalesInvoice inv = invoices.lockByCompanyIdAndId(companyId, found.getId())
                .orElseThrow(() -> NotFoundException.of("SalesInvoice", invoiceUid));
        PosSession session = requireReturnable(inv);
        requirePaidAtTill(inv);

        Long authorisedBy = requireReturnAuthority(inv, session, request.authorisedByUid());

        if (arBalance.hasAllocations(companyId, inv.getUid())) {
            throw new ConflictException(
                    "This sale cannot take a return because payments have been allocated against "
                            + "it. Please raise a credit note instead.");
        }

        // ---- lines ---------------------------------------------------------------------------
        List<SalesInvoiceLine> sold = invoiceLines.findByInvoiceIdOrderByLineNo(inv.getId());
        Map<String, SalesInvoiceLine> soldByUid = new HashMap<>();
        for (SalesInvoiceLine l : sold) {
            soldByUid.put(l.getUid(), l);
        }
        Map<Long, PosReturnedLineTotalsDto> prior = priorTotals(companyId, sold);
        final int scale = minorUnits.of(inv.getCurrency());

        record Picked(SalesInvoiceLine sold, PosReturnAmounts.Slice slice) { }
        List<Picked> picked = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PosSaleReturnRequest.LineDto req : request.lines()) {
            SalesInvoiceLine line = soldByUid.get(req.invoiceLineUid());
            if (line == null) {
                throw new ConflictException(
                        "One of the selected items is not on this sale. Refresh the sale and try again.");
            }
            if (!seen.add(line.getUid())) {
                throw new IllegalArgumentException(
                        "Each item can appear only once in a return. Combine the quantities.");
            }
            BigDecimal qty = req.qty();
            if (qty == null || qty.signum() <= 0) {
                throw new IllegalArgumentException("The quantity to return must be more than zero.");
            }
            if (qty.stripTrailingZeros().scale() > PosReturnAmounts.QTY_SCALE) {
                throw new IllegalArgumentException(
                        "The quantity to return has too many decimal places.");
            }
            PosReturnedLineTotalsDto before = prior.get(line.getId());
            BigDecimal remaining = line.getQuantity().subtract(
                    before == null ? BigDecimal.ZERO : before.qtyReturned());
            if (qty.compareTo(remaining) > 0) {
                throw new ConflictException(remaining.signum() <= 0
                        ? line.getProductName() + " has already been fully returned."
                        : "Only " + plain(remaining) + " of " + line.getProductName()
                                + " can still be returned.");
            }
            var slice = PosReturnAmounts.slice(line.getQuantity(), line.getQtyInBase(),
                    line.getNetAmount(), line.getLineDiscountAmount(), line.getVatAmount(),
                    qty, before, scale);
            if (slice.qtyBase().signum() <= 0) {
                throw new ConflictException(
                        "That quantity of " + line.getProductName() + " is too small to return.");
            }
            picked.add(new Picked(line, slice));
        }

        BigDecimal net = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        for (Picked p : picked) {
            net = net.add(p.slice().net());
            discount = discount.add(p.slice().discount());
            vat = vat.add(p.slice().vat());
        }
        BigDecimal gross = net.add(vat);
        if (gross.signum() <= 0) {
            throw new ConflictException(
                    "There is nothing to refund for the selected items.");
        }

        // ---- persist -------------------------------------------------------------------------
        Long actor = actorId();
        String reason = request.reason().strip();
        PosSaleReturn ret = returns.saveAndFlush(new PosSaleReturn(
                numbers.nextPosReturn(companyId), companyId, inv.getBranchId(), session.getId(),
                inv.getId(), reason, net, discount, vat, gross, inv.getCurrency().value(),
                authorisedBy, actor));

        short lineNo = 1;
        List<PosSaleReturnedPayload.LineItem> eventLines = new ArrayList<>();
        for (Picked p : picked) {
            var s = p.slice();
            returnLines.save(new PosSaleReturnLine(ret.getId(), companyId, lineNo++,
                    p.sold().getId(), p.sold().getProductId(), s.qty(), s.qtyBase(),
                    s.net(), s.discount(), s.vat(), s.gross(), actor));
            eventLines.add(new PosSaleReturnedPayload.LineItem(
                    p.sold().getProductId(), productUid(companyId, p.sold().getProductId()),
                    s.qtyBase()));
        }

        // The cash leaves the drawer: a REFUND payout lowers expected cash at cash-up.
        PosSessionPayout payout = payouts.save(PosSessionPayout.refundForSale(companyId,
                inv.getBranchId(), session.getId(), gross, "Item return " + ret.getReturnNo()
                        + ": " + reason, inv.getUid(), actor));
        ret.setPayoutId(payout.getId());

        outbox.publish(DomainEventType.POS_SALE_RETURNED, DomainEventType.AGG_POS_SALE_RETURN,
                ret.getId(), ret.getUid(), companyId, inv.getBranchId(),
                new PosSaleReturnedPayload(ret.getUid(), ret.getReturnNo(), inv.getUid(),
                        inv.getInvoiceNumber(), companyId, inv.getBranchId(),
                        inv.getCurrency().value(), Instant.now(), net, vat, gross, eventLines));

        audit.record(AuditEvent.of(AuditActions.POS_SALE_RETURN, "pos_sale_returns",
                        ret.getId(), ret.getUid())
                .detail(Map.of("invoiceUid", java.util.Objects.toString(inv.getUid(), ""),
                               "sessionUid", session.getUid(),
                               "returnNo", ret.getReturnNo(),
                               "gross", gross.toPlainString(),
                               "lineCount", String.valueOf(picked.size()),
                               "reason", reason,
                               "payoutUid", java.util.Objects.toString(payout.getUid(), ""),
                               "authorisedBy", authorisedBy == null ? "" : authorisedBy.toString(),
                               "authorisedByUid", request.authorisedByUid() == null
                                       ? "" : request.authorisedByUid())));

        // Stamp in the SAME transaction, so a concurrent duplicate sees the finished answer.
        if (key != null) {
            idempotency.stampInvoiceUid(companyId, key, ret.getUid());
        }
        return toDto(ret, inv, session.getUid(), payout.getUid());
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public PosSaleReturnsDto listForSale(String invoiceUid) {
        SalesInvoice inv = invoices.findByUid(invoiceUid)
                .orElseThrow(() -> NotFoundException.of("SalesInvoice", invoiceUid));
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        Long companyId = inv.getCompanyId();

        List<SalesInvoiceLine> sold = invoiceLines.findByInvoiceIdOrderByLineNo(inv.getId());
        Map<Long, PosReturnedLineTotalsDto> prior = priorTotals(companyId, sold);

        List<PosSaleReturnsDto.SoldLineDto> soldDtos = new ArrayList<>();
        boolean anythingLeft = false;
        for (SalesInvoiceLine l : sold) {
            PosReturnedLineTotalsDto t = prior.get(l.getId());
            BigDecimal returned = t == null ? BigDecimal.ZERO : t.qtyReturned();
            BigDecimal remaining = l.getQuantity().subtract(returned);
            anythingLeft |= remaining.signum() > 0;
            soldDtos.add(new PosSaleReturnsDto.SoldLineDto(l.getUid(), l.getLineNo(),
                    l.getProductId(), l.getProductCode(), l.getProductName(), l.getUnitName(),
                    l.getQuantity(), returned, remaining, l.getGrossAmount(),
                    t == null ? BigDecimal.ZERO : t.grossAmount()));
        }

        PosSession session = inv.getPosSessionId() == null ? null
                : sessions.findByCompanyIdAndId(companyId, inv.getPosSessionId()).orElse(null);
        List<PosSaleReturn> made = returns
                .findByCompanyIdAndSalesInvoiceIdOrderByIdAsc(companyId, inv.getId());
        List<PosSaleReturnDto> returnDtos = new ArrayList<>();
        for (PosSaleReturn r : made) {
            returnDtos.add(toDto(r, inv, session == null ? null : session.getUid(), null));
        }
        BigDecimal returnedGross = made.stream().map(PosSaleReturn::getGrossAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String blocked = blockedReason(inv, session, anythingLeft);
        return new PosSaleReturnsDto(inv.getUid(), inv.getInvoiceNumber(), !made.isEmpty(),
                returnedGross, blocked == null, blocked, soldDtos, returnDtos);
    }

    @Override
    @Transactional(readOnly = true)
    public PosSaleReturnDto getByUid(String returnUid) {
        // Scoped to the caller's active company in the query itself, then asserted again from the
        // loaded row — never from a caller-supplied id.
        RequestContext.Principal caller = RequestContext.get();
        Long companyId = caller == null ? null : caller.companyId();
        if (companyId == null) {
            throw new ForbiddenException("Select a company before opening a return.");
        }
        PosSaleReturn ret = returns.findByCompanyIdAndUid(companyId, returnUid)
                .orElseThrow(() -> NotFoundException.of("PosSaleReturn", returnUid));
        scopeGuard.assertCanActIn(caller, ret.getCompanyId());
        SalesInvoice inv = invoices.findByCompanyIdAndId(companyId, ret.getSalesInvoiceId())
                .orElseThrow(() -> NotFoundException.of("SalesInvoice",
                        String.valueOf(ret.getSalesInvoiceId())));
        String sessionUid = sessions.findByCompanyIdAndId(companyId, ret.getPosSessionId())
                .map(PosSession::getUid).orElse(null);
        String payoutUid = ret.getPayoutId() == null ? null
                : payouts.findByCompanyIdAndId(companyId, ret.getPayoutId())
                        .map(PosSessionPayout::getUid).orElse(null);
        return toDto(ret, inv, sessionUid, payoutUid);
    }

    @Override
    @Transactional(readOnly = true)
    public SalesInvoiceDto annotate(SalesInvoiceDto invoice) {
        if (invoice == null || invoice.posSessionId() == null || invoice.id() == null) {
            return invoice;
        }
        BigDecimal refunded = returns.sumGrossByCompanyIdAndInvoice(
                invoice.companyId(), invoice.id());
        return refunded.signum() > 0 || returns.existsByCompanyIdAndSalesInvoiceId(
                invoice.companyId(), invoice.id())
                ? invoice.withReturns(true, refunded) : invoice;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesInvoiceDto> annotate(List<SalesInvoiceDto> invoicesPage) {
        if (invoicesPage == null || invoicesPage.isEmpty()) {
            return invoicesPage;
        }
        Map<Long, List<SalesInvoiceDto>> byCompany = invoicesPage.stream()
                .filter(i -> i.posSessionId() != null && i.id() != null)
                .collect(Collectors.groupingBy(SalesInvoiceDto::companyId));
        Set<Long> returned = new HashSet<>();
        for (var e : byCompany.entrySet()) {
            returned.addAll(returns.findReturnedInvoiceIdsByCompanyId(e.getKey(),
                    e.getValue().stream().map(SalesInvoiceDto::id).toList()));
        }
        if (returned.isEmpty()) {
            return invoicesPage;
        }
        List<SalesInvoiceDto> out = new ArrayList<>(invoicesPage.size());
        for (SalesInvoiceDto i : invoicesPage) {
            out.add(returned.contains(i.id())
                    ? i.withReturns(true, returns.sumGrossByCompanyIdAndInvoice(
                            i.companyId(), i.id()))
                    : i);
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesInvoiceLineDto> annotateLines(List<SalesInvoiceLineDto> lines) {
        if (lines == null || lines.isEmpty()) {
            return lines;
        }
        Long companyId = lines.get(0).companyId();
        Map<Long, PosReturnedLineTotalsDto> totals = returnLines
                .totalsByCompanyIdAndSoldLines(companyId,
                        lines.stream().map(SalesInvoiceLineDto::id).toList())
                .stream().collect(Collectors.toMap(
                        PosReturnedLineTotalsDto::salesInvoiceLineId, t -> t));
        if (totals.isEmpty()) {
            return lines;
        }
        return lines.stream().map(l -> {
            PosReturnedLineTotalsDto t = totals.get(l.id());
            return t == null ? l : l.withQtyReturned(t.qtyReturned());
        }).toList();
    }

    // -------------------------------------------------------------------------
    // Guards
    // -------------------------------------------------------------------------

    /**
     * The state rules a return needs, in the order the till should hear about them. Returns the
     * sale's session (the drawer the refund comes out of).
     */
    private PosSession requireReturnable(SalesInvoice inv) {
        if (inv.getOrigin() != DocumentOrigin.POS || inv.getPosSessionId() == null) {
            throw new ConflictException(
                    "This invoice is not a POS sale, so its items cannot be returned at the till.");
        }
        PosSession session = sessions.findByCompanyIdAndId(inv.getCompanyId(), inv.getPosSessionId())
                .orElseThrow(() -> NotFoundException.of("PosSession",
                        String.valueOf(inv.getPosSessionId())));
        String blocked = blockedReason(inv, session, true);
        if (blocked != null) {
            throw new ConflictException(blocked);
        }
        return session;
    }

    /** The friendly reason a sale cannot take a return right now, or null when it can. */
    private static String blockedReason(SalesInvoice inv, PosSession session, boolean anythingLeft) {
        if (inv.getOrigin() != DocumentOrigin.POS || inv.getPosSessionId() == null) {
            return "This invoice is not a POS sale, so its items cannot be returned at the till.";
        }
        if (inv.getStatus() == InvoiceStatus.VOID) {
            return "This sale was already reversed, so there is nothing to return.";
        }
        if (inv.getStatus() != InvoiceStatus.FINALISED) {
            return "Only a completed sale can have items returned.";
        }
        if (session == null || session.getStatus() != PosSessionStatus.OPEN) {
            return "This sale's till session is closed. Items can only be returned while the "
                    + "session is open.";
        }
        if (!anythingLeft) {
            return "Every item on this sale has already been returned.";
        }
        return null;
    }

    /**
     * A cash refund only makes sense for money that was actually taken at the till: a sale left on
     * account, or settled as COMPLIMENTARY (nothing was paid), has no cash to give back.
     */
    private void requirePaidAtTill(SalesInvoice inv) {
        BigDecimal paid = BigDecimal.ZERO;
        for (var p : tenders.findByInvoiceId(inv.getId())) {
            if (p.getTenderType() == TenderType.COMPLIMENTARY) {
                throw new ConflictException(
                        "This sale was given as complimentary, so there is no payment to refund.");
            }
            paid = paid.add(p.getChangeAmount() != null
                    ? p.getAmount().subtract(p.getChangeAmount()) : p.getAmount());
        }
        if (paid.compareTo(inv.getGrossTotalAmount()) < 0) {
            throw new ConflictException(
                    "This sale was not paid in full at the till, so its items cannot be refunded "
                            + "in cash. Please raise a credit note instead.");
        }
    }

    /**
     * Same two rules as {@code PosSaleServiceImpl#requireReversalAuthority}, re-stated for a return
     * (own drawer unless supervisor; a second person's step-up otherwise). Kept as a deliberate
     * parallel rather than a shared helper so the reverse path's audited behaviour is untouched.
     *
     * @return the approving user's id, or null when the caller acted on their own supervisor
     *         authority
     */
    private Long requireReturnAuthority(SalesInvoice inv, PosSession session,
                                        String authoriserUid) {
        RequestContext.Principal caller = RequestContext.get();
        Long callerId = caller != null ? caller.userId() : null;
        boolean callerIsSupervisor = permissions.hasPermission(caller,
                PosSaleServiceImpl.REVERSAL_AUTHORITY_PERMISSION, System.currentTimeMillis());

        if (!callerIsSupervisor
                && (callerId == null || !callerId.equals(session.getCashierId()))) {
            auditRefusal(inv, "NOT_OWN_SESSION", null);
            throw new ForbiddenException(
                    "You can only return items of sales rung on your own till session. "
                    + "Ask a supervisor to return this one.");
        }
        if (callerIsSupervisor) {
            return null;
        }

        var verdict = stepUpAuth.verifyAuthoriserUid(authoriserUid,
                PosSaleServiceImpl.REVERSAL_AUTHORITY_PERMISSION, inv.getCompanyId());
        if (!verdict.authorised()) {
            auditRefusal(inv, "NOT_AUTHORISED", authoriserUid);
            throw new ForbiddenException(
                    "This refund needs a supervisor's approval. "
                    + "Ask a supervisor to approve it at the till, then try again.");
        }
        return users.idByUid(verdict.authoriserUid()).orElse(null);
    }

    /** Recorded independently: the refusal that follows rolls this transaction back. */
    private void auditRefusal(SalesInvoice inv, String outcome, String authoriserUid) {
        audit.recordIndependent(AuditEvent.of(AuditActions.POS_SALE_RETURN, "sales_invoices",
                        inv.getId(), inv.getUid())
                .detail(Map.of("outcome", outcome,
                               "authorisedByUid", authoriserUid == null ? "" : authoriserUid)));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private PosSaleReturnDto replay(SalesInvoice invoice, String returnUid) {
        if (returnUid == null) {
            throw inFlight();
        }
        PosSaleReturn ret = returns.findByCompanyIdAndUid(invoice.getCompanyId(), returnUid)
                .orElseThrow(PosSaleReturnServiceImpl::inFlight);
        if (!ret.getSalesInvoiceId().equals(invoice.getId())) {
            throw new ConflictException(
                    "That reference was already used for a different sale. Start the return again.");
        }
        String sessionUid = sessions.findByCompanyIdAndId(invoice.getCompanyId(),
                ret.getPosSessionId()).map(PosSession::getUid).orElse(null);
        String payoutUid = ret.getPayoutId() == null ? null
                : payouts.findByCompanyIdAndId(invoice.getCompanyId(), ret.getPayoutId())
                        .map(PosSessionPayout::getUid).orElse(null);
        return toDto(ret, invoice, sessionUid, payoutUid);
    }

    private static ConflictException inFlight() {
        return new ConflictException(
                "This return is still being recorded. Wait a moment and check the sale's returns "
                + "before entering it again — the cash has not been refunded twice.");
    }

    private static String markerKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String key = raw.trim();
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("That return reference is not valid.");
        }
        return KEY_PREFIX + key;
    }

    private Map<Long, PosReturnedLineTotalsDto> priorTotals(Long companyId,
                                                            List<SalesInvoiceLine> sold) {
        if (sold.isEmpty()) {
            return Map.of();
        }
        Map<Long, PosReturnedLineTotalsDto> out = new LinkedHashMap<>();
        for (var t : returnLines.totalsByCompanyIdAndSoldLines(companyId,
                sold.stream().map(SalesInvoiceLine::getId).toList())) {
            out.put(t.salesInvoiceLineId(), t);
        }
        return out;
    }

    /**
     * The product uid stock explodes recipes by. The sold line snapshots code and name, not the uid,
     * so it is read from the product master (company-scoped), as {@code finalise} does for the
     * {@code SALE.FINALISED} payload.
     */
    private String productUid(Long companyId, Long productId) {
        return products.findByCompanyIdAndId(companyId, productId)
                .map(Product::getUid)
                .orElseThrow(() -> NotFoundException.of("Product", String.valueOf(productId)));
    }

    private PosSaleReturnDto toDto(PosSaleReturn ret, SalesInvoice inv, String sessionUid,
                                   String payoutUid) {
        Long companyId = ret.getCompanyId();
        Map<Long, SalesInvoiceLine> soldById = new HashMap<>();
        for (SalesInvoiceLine l : invoiceLines.findByInvoiceIdOrderByLineNo(inv.getId())) {
            soldById.put(l.getId(), l);
        }
        Map<Long, String> sold2uid = new HashMap<>();
        soldById.forEach((id, l) -> sold2uid.put(id, l.getUid()));

        List<PosSaleReturnDto.LineDto> lineDtos = new ArrayList<>();
        for (PosSaleReturnLine l : returnLines
                .findByCompanyIdAndPosSaleReturnIdOrderByLineNo(companyId, ret.getId())) {
            SalesInvoiceLine s = soldById.get(l.getSalesInvoiceLineId());
            lineDtos.add(new PosSaleReturnDto.LineDto(l.getId(), l.getUid(), l.getLineNo(),
                    sold2uid.get(l.getSalesInvoiceLineId()), l.getProductId(),
                    s == null ? null : s.getProductCode(), s == null ? null : s.getProductName(),
                    s == null ? null : s.getUnitName(), l.getQtyReturned(), l.getNetAmount(),
                    l.getDiscountAmount(), l.getVatAmount(), l.getGrossAmount()));
        }

        List<Long> userIds = new ArrayList<>();
        if (ret.getAuthorisedBy() != null) {
            userIds.add(ret.getAuthorisedBy());
        }
        if (ret.getCreatedBy() != null) {
            userIds.add(ret.getCreatedBy());
        }
        Map<Long, String> names = users.displayNamesByIds(userIds);
        return new PosSaleReturnDto(ret.getId(), ret.getUid(), ret.getReturnNo(), companyId,
                ret.getBranchId(), sessionUid, inv.getUid(), inv.getInvoiceNumber(),
                ret.getReason(), ret.getNetAmount(), ret.getDiscountAmount(), ret.getVatAmount(),
                ret.getGrossAmount(), ret.getCurrency(), ret.getRefundTender(), payoutUid,
                ret.getAuthorisedBy() == null ? null : names.get(ret.getAuthorisedBy()),
                ret.getCreatedBy() == null ? null : names.get(ret.getCreatedBy()),
                ret.getCreatedAt() == null ? null : ret.getCreatedAt().toString(), lineDtos);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
