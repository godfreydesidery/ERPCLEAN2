package com.erp.modules.sales.service;

import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.PosSaleLookupDto;
import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import com.erp.modules.sales.domain.entity.PosSaleIdempotency;
import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.DocumentOrigin;
import com.erp.modules.sales.domain.enums.PosSaleLookupVerdict;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.domain.exception.PosSaleFlowException;
import com.erp.modules.sales.repository.PosSaleIdempotencyRepository;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.service.StepUpAuthService;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * POS quick-sale orchestrator (ADR-0029 D-5, FR-SD-15).
 *
 * <p>Delegates invoice creation / line building / payment / finalise to
 * {@link SalesInvoiceService} (same module), then sets the POS-specific fields
 * (origin=POS, posSessionId) directly via the repository before finalise.
 *
 * <h2>K11 — the unfinished-sale loop</h2>
 * The till's "Unfinished sale" dialog is a recovery feature that had no terminating state. Three
 * things compounded, and all three are addressed here:
 * <ol>
 *   <li><b>Ordering.</b> The OPEN-session check ran before the idempotency marker was consulted, so
 *       the stored answer to "did this post?" became permanently unreachable once the shift closed.
 *       The marker is now the FIRST thing read — see {@link #processSale}.</li>
 *   <li><b>Status collision.</b> "session not open", "not enough stock", "below cost" and a real
 *       "still in flight" were all HTTP 409. Every business rejection now carries
 *       {@code PosSaleFlowStatus.REJECTED} (422) and only a genuine in-flight attempt carries
 *       {@code IN_FLIGHT} (409), so the client re-arms the pending slot in exactly one case.</li>
 *   <li><b>No cheap read path.</b> {@link #lookupByIdempotencyKey} answers the question without
 *       re-running a single business guard.</li>
 * </ol>
 *
 * <p><b>Stale-replay guard.</b> A pending basket that is finally submitted days later would be
 * re-priced and re-stocked into the current period. Three cheap gates refuse that with
 * {@code STALE_REPLAY} rather than posting it silently: an abandoned marker older than
 * {@code erp.pos.sale.replay-max-age}, a client-declared {@code capturedAt} older than
 * {@code erp.pos.sale.max-age}, and a session that has been open longer than
 * {@code erp.pos.session.max-open-age}. Each is configurable and each is disabled by setting it to
 * zero, so a shop with unusual hours can turn one off without a code change.
 */
@Service
@Transactional
public class PosSaleServiceImpl implements PosSaleService {

    /**
     * The supervisor authority a till reversal needs — both to reverse ANOTHER cashier's sale and to
     * approve a refund via manager step-up.
     *
     * <p>Seeded in {@code R__seed_permissions.sql} and granted to SALES_MANAGER and BRANCH_MANAGER,
     * never to CASHIER. That last part is the whole point: {@code POS.SALE.VOID} was the
     * obvious-looking choice and is the wrong one, because the CASHIER bundle holds it — a cashier
     * would have approved their own refund by retyping their own password, and reached into a
     * stranger's drawer on their own authority.
     *
     * <p>Must stay equal to the code the till's step-up policy names for
     * {@code GatedAction.saleReverse} ({@code pos_app/lib/core/config/step_up_policy.dart}). A drift
     * shows up at the till as "the manager typed the right password and was refused anyway".
     */
    public static final String REVERSAL_AUTHORITY_PERMISSION = "SALES.INVOICE.VOID";

    private final PosSessionRepository  posSessionRepo;
    private final SalesInvoiceRepository invoiceRepo;
    private final SalesInvoiceService    invoiceService;
    private final ProductRepository      products;
    private final UnitOfMeasureRepository units;
    private final CustomerRepository     customers;
    private final CompanyRepository      companies;
    private final ScopeGuard             scopeGuard;
    private final AuditService           audit;
    private final PosSaleIdempotencyRepository idempotency;
    /** ADR-0044 D-3a: runtime permission check for POS.SALE.AGE_OVERRIDE. */
    private final PermissionResolver     permissionResolver;
    /** BR-SALES-06: turns the request's numeric agent id into the uid the invoice service takes. */
    private final AgentRepository        agents;
    /**
     * Server-side re-verification of a manager step-up (K1 follow-up). Reached through the IAM
     * service INTERFACE and an {@code ..domain.dto..} return type only — no IAM entity or repository
     * crosses into this module, and the authority question stays answered in exactly one place
     * instead of being reimplemented here.
     */
    private final StepUpAuthService      stepUpAuth;

    /** How long a reserved-but-unstamped marker may sit before it reads as abandoned, not in-flight. */
    private final Duration replayMaxAge;
    /** How old a client-declared basket capture may be before the sale is refused. */
    private final Duration saleMaxAge;
    /** How long a session may stay OPEN before it stops accepting new sales. */
    private final Duration maxOpenSessionAge;

    public PosSaleServiceImpl(PosSessionRepository posSessionRepo,
                               SalesInvoiceRepository invoiceRepo,
                               SalesInvoiceService invoiceService,
                               ProductRepository products,
                               UnitOfMeasureRepository units,
                               CustomerRepository customers,
                               CompanyRepository companies,
                               ScopeGuard scopeGuard,
                               AuditService audit,
                               PosSaleIdempotencyRepository idempotency,
                               PermissionResolver permissionResolver,
                               AgentRepository agents,
                               StepUpAuthService stepUpAuth,
                               @Value("${erp.pos.sale.replay-max-age:PT15M}") Duration replayMaxAge,
                               @Value("${erp.pos.sale.max-age:PT12H}") Duration saleMaxAge,
                               @Value("${erp.pos.session.max-open-age:PT36H}") Duration maxOpenSessionAge) {
        this.agents            = agents;
        this.posSessionRepo    = posSessionRepo;
        this.invoiceRepo       = invoiceRepo;
        this.invoiceService    = invoiceService;
        this.products          = products;
        this.units             = units;
        this.customers         = customers;
        this.companies         = companies;
        this.scopeGuard        = scopeGuard;
        this.audit             = audit;
        this.idempotency       = idempotency;
        this.permissionResolver = permissionResolver;
        this.stepUpAuth        = stepUpAuth;
        this.replayMaxAge      = replayMaxAge;
        this.saleMaxAge        = saleMaxAge;
        this.maxOpenSessionAge = maxOpenSessionAge;
    }

    @Override
    public SalesInvoiceDto processSale(String idempotencyKey, PosSaleRequest req) {
        boolean idem = idempotencyKey != null && !idempotencyKey.isBlank();

        // 1 — Resolve the session and prove the caller may act in its company.
        // Identity and authorisation ONLY. Every business guard lives below the idempotency check
        // on purpose (K11 cause 1): whether this sale already posted must be answerable regardless
        // of what the till has done since.
        var session = posSessionRepo.findByUid(req.sessionUid())
                .orElseThrow(() -> NotFoundException.of("PosSession", req.sessionUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), session.getCompanyId());
        Long companyId = session.getCompanyId();

        // 2 — IDEMPOTENCY FIRST (ADR-0042 D-1 + K11).
        // A marker that already carries an invoice is a definitive "yes, this posted" and is returned
        // even if the session has since closed. Otherwise claim the key for this attempt; the claim
        // is inserted in THIS transaction, so it rolls back with the sale if anything below refuses.
        if (idem) {
            var claimed = idempotency.findByCompanyIdAndIdemKey(companyId, idempotencyKey);
            if (claimed.isPresent()) {
                return answerFromMarker(claimed.get());
            }
            // ON CONFLICT DO NOTHING returns 0 on a taken key, blocking first on an in-flight winner.
            if (idempotency.tryReserve(companyId, idempotencyKey) == 0) {
                var marker = idempotency.findByCompanyIdAndIdemKey(companyId, idempotencyKey)
                        .orElseThrow(PosSaleFlowException::inFlight);
                return answerFromMarker(marker);
            }
        }

        // 3 — Business guards. Only reached when this call is genuinely ringing a NEW sale, so a
        // rejection here can never hide an already-completed one.
        requireUsableSession(session);
        requireFreshBasket(req);

        // 4 — Ring it. Every business refusal raised below (here or inside the invoice service) is
        // re-badged as REJECTED so the client can tell it apart from "still in flight" without
        // reading the message (K11 cause 2). NotFound/Forbidden pass through untouched.
        //
        // The sale is deliberately NOT extracted into its own method: the entity lookups below are
        // recorded against this method signature in the ArchUnit freeze store, and moving them would
        // register as brand-new tenant-scoping violations and fail the build.
        try {
            // 5 — Resolve customer UID (PosSaleRequest carries customerId)
            var customer = customers.findById(req.customerId())
                    .orElseThrow(() -> NotFoundException.of("Customer", String.valueOf(req.customerId())));
            var company = companies.findById(companyId)
                    .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(companyId)));

            // Resolve agent — PosSaleRequest carries a numeric agentId, invoiceService takes a uid.
            // That conversion was missing: null was passed straight through, so the field was silently
            // ignored and EVERY POS sale fell back to "the signed-in user's own internal agent". A
            // cashier without one could not sell at all, and had no way to name an agent instead
            // (BR-SALES-06 rejects the sale outright).
            //
            // Scoped to the session's company on the way through. The id arrives from the caller, so
            // resolving it unscoped would let one company's till reference another company's agent —
            // scope from the row you loaded, never from the parameter you were handed.
            String agentUid = null;
            if (req.agentId() != null) {
                agentUid = agents.findByCompanyIdAndId(companyId, req.agentId())
                        .orElseThrow(() -> NotFoundException.of("Agent", String.valueOf(req.agentId())))
                        .getUid();
            }

            // 6 — Create DRAFT invoice via service (handles number allocation + audit)
            var createReq = new CreateSalesInvoiceRequest(
                    company.getUid(), customer.getUid(), agentUid, req.currency(), req.notes(), null);
            var draftDto = invoiceService.create(createReq);
            String invoiceUid = draftDto.uid();

            // 7 — Stamp origin=POS and posSessionId on the entity (POS-specific fields)
            SalesInvoice invoice = invoiceRepo.findByUid(invoiceUid)
                    .orElseThrow(() -> NotFoundException.of("SalesInvoice", invoiceUid));
            invoice.setOrigin(DocumentOrigin.POS);
            invoice.setPosSessionId(session.getId());
            invoice.setUpdatedAt(Instant.now());
            invoice.setUpdatedBy(actorId());
            // flush so finalise sees origin=POS
            invoiceRepo.saveAndFlush(invoice);

            // 8 — Age-restriction gate (ADR-0044 D-3a, BR-11).
            // Resolve once: verified flag from request; override from caller's permission set.
            boolean ageVerified = Boolean.TRUE.equals(req.ageVerified());
            boolean ageOverride = permissionResolver.hasPermission(
                    RequestContext.get(), "POS.SALE.AGE_OVERRIDE", System.currentTimeMillis());

            // 9 — Add lines (resolve products first to run the age gate before any invoice mutation)
            for (var line : req.lines()) {
                var product = products.findById(line.productId())
                        .orElseThrow(() -> NotFoundException.of("Product", String.valueOf(line.productId())));
                if (product.getRestrictedKind().isRestricted() && !ageVerified && !ageOverride) {
                    throw new ConflictException(
                            "Age-restricted item '" + product.getName() + "' requires age verification"
                            + " — resend with ageVerified=true, or the cashier needs POS.SALE.AGE_OVERRIDE.");
                }
                var unit = units.findById(line.unitId())
                        .orElseThrow(() -> NotFoundException.of("Unit", String.valueOf(line.unitId())));
                // K7: carry the per-line manager authorisation through to addLine, which is where the
                // discount ceiling is enforced. The till and the web invoice screen therefore share one
                // implementation of the policy — a POS-only check would be decorative, since the same
                // ungated discount input exists on the web POS.
                var lineReq = new AddInvoiceLineRequest(
                        product.getUid(), unit.getUid(),
                        line.quantity(), line.lineDiscountAmount(), null,
                        line.discountAuthorisedByUid());
                invoiceService.addLine(invoiceUid, lineReq);
            }

            // 10 — Settle the sale (re-read totals after lines). Multi-tender when provided
            // (ADR-0042 D-3), otherwise a single exact CASH payment — the original behaviour. The
            // payment layer already supports every TenderType + instrument refs (ADR-0041 D3) and the
            // over-tender/change rule (BR-SALES-07, CASH only); we just need the tenders to cover
            // the gross.
            SalesInvoice reloaded = invoiceRepo.findByUid(invoiceUid)
                    .orElseThrow(() -> NotFoundException.of("SalesInvoice", invoiceUid));
            BigDecimal grossTotal = reloaded.getGrossTotalAmount();
            var tenders = req.tenders();
            if (tenders != null && !tenders.isEmpty()) {
                BigDecimal tendered = BigDecimal.ZERO;
                for (var t : tenders) {
                    invoiceService.addPayment(invoiceUid, new AddPaymentRequest(
                            t.tenderType(), t.amount(), req.currency(), t.reference(),
                            t.cashBankAccountId(), t.chequeId(), t.mobileMoneyRef(), t.cardRef()));
                    tendered = tendered.add(t.amount());
                }
                if (tendered.compareTo(grossTotal) < 0) {
                    throw new ConflictException("Tenders (" + tendered.toPlainString()
                            + ") do not cover the gross total (" + grossTotal.toPlainString() + ").");
                }
            } else {
                invoiceService.addPayment(invoiceUid,
                        new AddPaymentRequest(TenderType.CASH, grossTotal, req.currency(), null));
            }

            // 11 — Finalise. Carries the below-cost approval through (V93): the policy is enforced
            // inside finalise (BelowCostGuard), which needs both the cashier's acknowledgement and
            // their SALES.BELOW_COST.OVERRIDE grant before an at-or-below-cost line will settle.
            boolean belowCostApproved = Boolean.TRUE.equals(req.belowCostApproved());
            invoiceService.finalise(invoiceUid, new FinaliseInvoiceRequest(null, belowCostApproved));

            // 12 — Stamp the created invoice onto the idempotency marker (same TX as the sale, so a
            // concurrent duplicate sees it the instant this transaction commits — ADR-0042 D-1).
            if (idem) {
                idempotency.stampInvoiceUid(companyId, idempotencyKey, invoiceUid);
            }

            // 13 — Audit POS sale (includes age-gate decision for ADR-0044 D-3a traceability)
            // K7: the discount was missing from this row entirely — a till receipt could be discounted
            // to nothing and the sale-level audit trail would look identical to a full-price one.
            // detail is JSONB, so recording it needs no schema. discountAuthorised reports whether ANY
            // line carried a manager authorisation; the per-line detail (who, how much, against which
            // ceiling) is on the SALES.INVOICE.LINE.ADD / SALES.DISCOUNT.OVERRIDE rows.
            BigDecimal lineDiscountTotal = totalLineDiscount(req);
            boolean discountAuthorised = req.lines().stream()
                    .anyMatch(l -> l.discountAuthorisedByUid() != null
                            && !l.discountAuthorisedByUid().isBlank());
            audit.record(AuditEvent.of(AuditActions.POS_SALE_FINALISE, "sales_invoices",
                    reloaded.getId(), invoiceUid)
                    .detail(Map.of("sessionUid",  req.sessionUid(),
                                   "gross",       grossTotal.toPlainString(),
                                   "lineDiscountTotal", lineDiscountTotal.toPlainString(),
                                   "discountAuthorised", String.valueOf(discountAuthorised),
                                   "ageVerified", String.valueOf(ageVerified),
                                   "ageOverride", String.valueOf(ageOverride),
                                   "belowCostApproved", String.valueOf(belowCostApproved))));

            return invoiceService.getByUid(invoiceUid);
        } catch (PosSaleFlowException ex) {
            throw ex;
        } catch (ConflictException | IllegalStateException ex) {
            throw PosSaleFlowException.rejected(ex.getMessage(), refusalCodeOf(ex));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PosSaleLookupDto lookupByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("A sale reference is required.");
        }
        String key = idempotencyKey.trim();
        if (key.length() > 80) {
            throw new IllegalArgumentException("That sale reference is not valid.");
        }
        // Scope from the caller's active company (never a caller-supplied id): one company can never
        // probe another's sale references.
        var principal = RequestContext.get();
        Long companyId = (principal != null) ? principal.companyId() : null;
        if (companyId == null) {
            throw new ForbiddenException("Select a company before looking up a sale.");
        }

        Optional<PosSaleIdempotency> marker =
                idempotency.findByCompanyIdAndIdemKey(companyId, key);
        if (marker.isEmpty()) {
            // The marker and the invoice commit together, so no marker means nothing committed.
            return new PosSaleLookupDto(key, PosSaleLookupVerdict.NEVER_POSTED, null, null, null,
                    "No sale was recorded under this reference. Ring the sale again using the same"
                    + " reference — that way it can never be recorded twice.");
        }
        String invoiceUid = marker.get().getInvoiceUid();
        if (invoiceUid == null) {
            return new PosSaleLookupDto(key, PosSaleLookupVerdict.UNKNOWN, null, null, null,
                    "This sale is still being worked on. Wait a moment and check again.");
        }
        SalesInvoiceDto invoice = invoiceService.getByUid(invoiceUid);
        return new PosSaleLookupDto(key, PosSaleLookupVerdict.POSTED, invoice.uid(),
                invoice.invoiceNumber(), invoice.grossTotalAmount(),
                "This sale was already completed as receipt " + invoice.invoiceNumber()
                + ". Do not ring it again.");
    }

    /**
     * Convenience overload, overridden here ON PURPOSE rather than left as the interface default.
     *
     * <p>A {@code default} method is not declared on this class, so the class-level
     * {@link Transactional} never applies to it, and the default body's call to the real method goes
     * through {@code this} — bypassing the Spring proxy. The result was a reversal running with NO
     * transaction at all, which blew up on the first {@code MANDATORY} audit write
     * (IllegalTransactionStateException, caught by PosSaleServiceIT). Declaring the overload here
     * puts it back under the proxy, so a transaction is open before the delegate runs and the audit
     * writes join it.
     */
    @Override
    public void reverseSale(String invoiceUid, String reason) {
        reverseSale(invoiceUid, new VoidInvoiceRequest(reason));
    }

    @Override
    public void reverseSale(String invoiceUid, VoidInvoiceRequest request) {
        String reason = request == null ? null : request.reason();
        SalesInvoice inv = invoiceRepo.findByUid(invoiceUid)
                .orElseThrow(() -> NotFoundException.of("SalesInvoice", invoiceUid));
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());

        // Must be a POS sale (ADR-0042 D-2) — non-POS invoices use the standard back-office void.
        if (inv.getOrigin() != DocumentOrigin.POS || inv.getPosSessionId() == null) {
            // invoiceUid intentionally not surfaced (error-hygiene rule)
            throw new ConflictException(
                    "This invoice is not a POS sale; use the standard invoice void.");
        }

        // Drawer rule (ADR-0042 D-2): the originating session must be OPEN so the till absorbs the
        // cash refund. The void already reverses the GL cash leg (cash sale -> SALES_REVERSAL credits
        // Cash) and stock; a settled/closed session is a back-office void, not a till reversal.
        var session = posSessionRepo.findById(inv.getPosSessionId())
                .orElseThrow(() -> NotFoundException.of(
                        "PosSession", String.valueOf(inv.getPosSessionId())));
        if (session.getStatus() != PosSessionStatus.OPEN) {
            throw new ConflictException(
                    "POS session is not OPEN; reverse a settled session's sale via back-office void.");
        }

        String authoriserUsername = requireReversalAuthority(inv, session, request);

        // POS-specific path (busy-day-simulation fix): skips the AR-oriented settled-tender guard
        // that made this feature unsatisfiable (every POS sale is paid at the till). The AR
        // allocation guard still applies inside voidPosInvoice.
        invoiceService.voidPosInvoice(invoiceUid, new VoidInvoiceRequest(reason));

        audit.record(AuditEvent.of(AuditActions.POS_SALE_REVERSE, "sales_invoices",
                        inv.getId(), invoiceUid)
                .detail(Map.of("sessionUid", session.getUid(),
                               "reason", reason == null ? "" : reason,
                               // Who authorised it, by name. "" means the caller reversed on their
                               // own supervisor authority — the audit actor IS the authoriser.
                               "authorisedBy", authoriserUsername == null ? "" : authoriserUsername,
                               "authorisedByUid", request == null || request.authorisedByUid() == null
                                       ? "" : request.authorisedByUid())));
    }

    /**
     * The two authorisation rules a till reversal has to satisfy, and the reason each exists.
     *
     * <p><b>1 — Own drawer.</b> Until now {@code reverseSale} checked company scope, POS origin and
     * "session is OPEN", and nothing else. Any holder of {@code POS.SALE.VOID} — i.e. every cashier
     * — could reverse a sale belonging to a <em>different</em> cashier's open session: money out of a
     * drawer they are not accountable for, against a receipt they never rang, silently changing that
     * cashier's expected cash and moving stock. A cashier may therefore reverse only sales rung on
     * their own session, unless they hold {@value #REVERSAL_AUTHORITY_PERMISSION} — the supervisor
     * authority the CASHIER bundle deliberately does not carry.
     *
     * <p><b>2 — A second person.</b> A refund is money out of the drawer against a receipt that
     * already exists, which makes it the textbook shrinkage route, so it cannot rest on the operator's
     * own say-so. The till already asked for a manager password — but that prompt lived entirely in
     * the client, so curl, a script or a second client skipped it. The approval now travels on the
     * request as {@code authorisedByUid} and is re-resolved here, exactly as
     * {@code DiscountAuthorisationGuard} re-resolves a discount approval: the named user must be
     * real, active, someone OTHER than the caller, and must genuinely hold
     * {@value #REVERSAL_AUTHORITY_PERMISSION} in THIS invoice's company.
     *
     * <p>A caller who holds that authority themselves is exempt from rule 2: they are the person a
     * cashier would have called over, and demanding a second supervisor would deadlock a shop that
     * has one manager on shift.
     *
     * @return the authoriser's username when a step-up approved it, or {@code null} when the caller
     *         acted on their own supervisor authority
     */
    private String requireReversalAuthority(SalesInvoice inv, PosSession session,
                                            VoidInvoiceRequest request) {
        RequestContext.Principal caller = RequestContext.get();
        Long callerId = caller != null ? caller.userId() : null;

        boolean callerIsSupervisor = permissionResolver.hasPermission(
                caller, REVERSAL_AUTHORITY_PERMISSION, System.currentTimeMillis());

        if (!callerIsSupervisor
                && (callerId == null || !callerId.equals(session.getCashierId()))) {
            auditReversalRefusal(inv, "NOT_OWN_SESSION", null);
            throw new ForbiddenException(
                    "You can only reverse sales rung on your own till session. "
                    + "Ask a supervisor to reverse this one.");
        }

        if (callerIsSupervisor) {
            return null;                       // the caller IS the authority for this action
        }

        String authoriserUid = request == null ? null : request.authorisedByUid();
        var verdict = stepUpAuth.verifyAuthoriserUid(
                authoriserUid, REVERSAL_AUTHORITY_PERMISSION, inv.getCompanyId());
        if (!verdict.authorised()) {
            auditReversalRefusal(inv, "NOT_AUTHORISED", authoriserUid);
            throw new ForbiddenException(
                    "This refund needs a supervisor's approval. "
                    + "Ask a supervisor to approve it at the till, then try again.");
        }
        return verdict.authoriserUsername();
    }

    /**
     * Records a refused reversal with {@code recordIndependent}: the {@link ForbiddenException} that
     * follows rolls this transaction back, so an audit row written inside it would vanish with the
     * refusal — and "somebody tried to reverse a stranger's sale" is exactly what a shrinkage
     * investigation is looking for.
     */
    private void auditReversalRefusal(SalesInvoice inv, String outcome, String authoriserUid) {
        audit.recordIndependent(AuditEvent.of(AuditActions.POS_SALE_REVERSE, "sales_invoices",
                        inv.getId(), inv.getUid())
                .detail(Map.of("outcome", outcome,
                               "authorisedByUid", authoriserUid == null ? "" : authoriserUid)));
    }

    // -------------------------------------------------------------------------
    // K11 helpers
    // -------------------------------------------------------------------------

    /**
     * Turns an existing marker into an answer.
     *
     * <p>Stamped marker → the original invoice, whatever state the session is in now. Unstamped and
     * young → a real in-flight attempt worth retrying. Unstamped and old → an attempt that never
     * finished: reporting that as "still being processed" forever is precisely the loop K11 is
     * about, so it terminates as STALE_REPLAY instead.
     */
    private SalesInvoiceDto answerFromMarker(PosSaleIdempotency marker) {
        if (marker.getInvoiceUid() != null) {
            return invoiceService.getByUid(marker.getInvoiceUid());
        }
        if (isOlderThan(marker.getCreatedAt(), replayMaxAge)) {
            throw PosSaleFlowException.staleReplay(
                    "An earlier attempt at this sale never finished and is now too old to continue."
                    + " Check whether the customer was served, then ring the sale again.");
        }
        throw PosSaleFlowException.inFlight();
    }

    /**
     * The session must be open, and it must not have been open so long that ringing into it would
     * drop a sale into a different trading day from the one the session (and its Z-read) covers.
     */
    private void requireUsableSession(PosSession session) {
        if (session.getStatus() != PosSessionStatus.OPEN) {
            throw PosSaleFlowException.rejected(
                    "This till session is closed, so no more sales can be rung on it."
                    + " Open a new session to keep selling.");
        }
        if (isOlderThan(session.getOpenedAt(), maxOpenSessionAge)) {
            throw PosSaleFlowException.staleReplay(
                    "This till session has been open too long to take new sales."
                    + " Close and reconcile it, then open a new session.");
        }
    }

    /** Refuses a basket the till captured too long ago to be re-priced into today. */
    private void requireFreshBasket(PosSaleRequest req) {
        if (isOlderThan(req.capturedAt(), saleMaxAge)) {
            throw PosSaleFlowException.staleReplay(
                    "This sale was started too long ago to be completed now."
                    + " Prices and stock may have changed — please ring it again.");
        }
    }

    /**
     * True when {@code moment} is further in the past than {@code maxAge}. A null moment, a null or
     * non-positive {@code maxAge} (the documented way to switch a gate off), and any future moment
     * (clock skew on the till) all answer false — a guard must never be the reason a live till
     * cannot sell.
     */
    private static boolean isOlderThan(Instant moment, Duration maxAge) {
        if (moment == null || maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
            return false;
        }
        return moment.plus(maxAge).isBefore(Instant.now());
    }

    // -------------------------------------------------------------------------

    /** Sum of the per-line discounts the till asked for, for the sale-level audit row. */
    private static BigDecimal totalLineDiscount(PosSaleRequest req) {
        BigDecimal total = BigDecimal.ZERO;
        for (var line : req.lines()) {
            if (line.lineDiscountAmount() != null) {
                total = total.add(line.lineDiscountAmount());
            }
        }
        return total;
    }

    private Long actorId() {
        var p = RequestContext.get();
        return (p != null) ? p.userId() : null;
    }

    /**
     * The refusing rule's machine-readable token, when the rule named itself.
     *
     * <p>A refused sale is REJECTED whatever refused it — nothing was written, so the till must not
     * arm a pending sale — but the outcome alone does not tell a client whether there is a remedy it
     * could offer. The discount policy carries one (a manager step-up), so its token travels with
     * the refusal and the client branches on that instead of searching the English message for the
     * word "approval" (UAT finding #13). {@code null} for every other refusal, which leaves those
     * responses byte-for-byte as they were.
     *
     * <p>Fully qualified rather than imported on purpose: this file's {@code findById} call sites are
     * pinned by line number in the ArchUnit tenant-scoping freeze store, and an added import line
     * would shift every one of them.
     */
    private static String refusalCodeOf(RuntimeException ex) {
        return ex instanceof com.erp.modules.sales.domain.exception.DiscountApprovalException discount
                ? discount.getCode().name()
                : null;
    }
}
