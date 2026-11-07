package com.erp.modules.sales.service;

import com.erp.modules.gl.domain.dto.FiscalPeriodDto;
import com.erp.modules.gl.domain.enums.PeriodStatus;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.OfflineSyncRequest.OfflinePeriod;
import com.erp.modules.sales.domain.dto.OfflineSyncRequest.OfflineSale;
import com.erp.modules.sales.domain.dto.OfflineSyncRequest.OfflineSession;
import com.erp.modules.sales.domain.dto.OfflineSyncResultDto.SaleResult;
import com.erp.modules.sales.domain.dto.OfflineSyncResultDto.SessionResult;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceLineDto;
import com.erp.modules.sales.domain.entity.PosOfflinePeriod;
import com.erp.modules.sales.domain.entity.PosOfflineReview;
import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.entity.PosTill;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.DocumentOrigin;
import com.erp.modules.sales.domain.enums.OfflineReviewKind;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.repository.PosOfflinePeriodRepository;
import com.erp.modules.sales.repository.PosOfflineReviewRepository;
import com.erp.modules.sales.repository.PosSaleIdempotencyRepository;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.SalesInvoiceLineRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.time.CompanyCalendar;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts ONE offline sale (or offline shift, or offline period) in its own transaction (ADR-0068
 * D-4, D-5). Called by {@link PosOfflineSyncServiceImpl} for each item of a till's batch, so one sale
 * that cannot be posted never undoes the others.
 *
 * <p>A separate bean on purpose: {@code REQUIRES_NEW} only takes effect through the Spring proxy, so
 * these methods must not be called from inside the orchestrator class itself.
 */
@Component
public class PosOfflineSaleIngestor {

    /** Offline selling allows only these tenders (D-3); complimentary and credit are blocked. */
    static final Set<TenderType> OFFLINE_TENDERS =
            EnumSet.of(TenderType.CASH, TenderType.MOBILE_MONEY, TenderType.CARD);

    /** A till clock may run a little fast; a sale "sold" further in the future than this is refused. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(10);

    private final PosSessionRepository sessions;
    private final SalesInvoiceRepository invoices;
    private final SalesInvoiceService invoiceService;
    private final ProductRepository products;
    private final UnitOfMeasureRepository units;
    private final CustomerRepository customers;
    private final CompanyRepository companies;
    private final PosSaleIdempotencyRepository idempotency;
    private final PosOfflineReviewRepository reviews;
    private final PosOfflinePeriodRepository periods;
    private final NegativeStockGuard stockGuard;
    private final BelowCostGuard belowCostGuard;
    private final SalesInvoiceLineRepository invoiceLines;
    private final FiscalCalendarService fiscalCalendar;
    private final CompanyCalendar calendar;
    private final SalesDepthNumberGenerator numberGen;
    private final AuditService audit;

    public PosOfflineSaleIngestor(PosSessionRepository sessions, SalesInvoiceRepository invoices,
                                  SalesInvoiceService invoiceService, ProductRepository products,
                                  UnitOfMeasureRepository units, CustomerRepository customers,
                                  CompanyRepository companies,
                                  PosSaleIdempotencyRepository idempotency,
                                  PosOfflineReviewRepository reviews,
                                  PosOfflinePeriodRepository periods,
                                  NegativeStockGuard stockGuard, BelowCostGuard belowCostGuard,
                                  SalesInvoiceLineRepository invoiceLines,
                                  FiscalCalendarService fiscalCalendar, CompanyCalendar calendar,
                                  SalesDepthNumberGenerator numberGen, AuditService audit) {
        this.sessions = sessions;
        this.invoices = invoices;
        this.invoiceService = invoiceService;
        this.products = products;
        this.units = units;
        this.customers = customers;
        this.companies = companies;
        this.idempotency = idempotency;
        this.reviews = reviews;
        this.periods = periods;
        this.stockGuard = stockGuard;
        this.belowCostGuard = belowCostGuard;
        this.invoiceLines = invoiceLines;
        this.fiscalCalendar = fiscalCalendar;
        this.calendar = calendar;
        this.numberGen = numberGen;
        this.audit = audit;
    }

    // -------------------------------------------------------------------------
    // Shifts opened offline (D-4)
    // -------------------------------------------------------------------------

    /**
     * Finds or creates the server shift for a shift the till opened offline. Idempotent on the till's
     * client key. Refused (the till's sales in it are then held) when the till already has another
     * OPEN shift on the server — one open shift per till is a database rule.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SessionResult ensureSession(PosTill till, OfflineSession s, Long actorId) {
        Optional<PosSession> existing =
                sessions.findByCompanyIdAndOfflineClientKey(till.getCompanyId(), s.clientKey());
        if (existing.isPresent()) {
            PosSession found = existing.get();
            if (!found.getPosTillId().equals(till.getId())) {
                throw new IllegalStateException("That offline shift belongs to a different till.");
            }
            return new SessionResult(s.clientKey(), found.getUid(), found.getSessionNumber());
        }
        if (sessions.findByPosTillIdAndStatus(till.getId(), PosSessionStatus.OPEN).isPresent()) {
            throw new IllegalStateException(
                    "This till already has an open shift on the server, so the shift it opened"
                    + " offline could not be created.");
        }
        if (s.openedAt().isAfter(Instant.now().plus(CLOCK_SKEW))) {
            throw new IllegalArgumentException("The offline shift's opening time is in the future.");
        }
        PosSession created = sessions.save(PosSession.openedOffline(
                till.getCompanyId(), till.getBranchId(), till.getId(), actorId,
                numberGen.nextPosSession(till.getCompanyId()), s.openingFloatAmount(),
                s.openedAt(), s.clientKey(), actorId));
        audit.record(AuditEvent.of(AuditActions.POS_OFFLINE_SESSION_OPEN, "pos_sessions",
                        created.getId(), created.getUid())
                .detail(Map.of("tillUid", till.getUid(), "clientKey", s.clientKey(),
                        "openedAt", s.openedAt().toString())));
        return new SessionResult(s.clientKey(), created.getUid(), created.getSessionNumber());
    }

    // -------------------------------------------------------------------------
    // Offline sales (D-3, D-5)
    // -------------------------------------------------------------------------

    /**
     * The sale is being posted by another request right now. Not a refusal: the orchestrator leaves
     * it out of the answer, so the till keeps it and sends it again.
     */
    public static final class InFlightException extends RuntimeException {
        InFlightException() {
            super("This offline sale is already being posted.");
        }
    }

    /** Result of {@link #ingest}: either a posted/already-posted answer, or a reason to hold. */
    public record IngestOutcome(SaleResult result, String holdReason, Long sessionId) {
        static IngestOutcome done(SaleResult r) {
            return new IngestOutcome(r, null, null);
        }

        static IngestOutcome hold(String reason, Long sessionId) {
            return new IngestOutcome(null, reason, sessionId);
        }
    }

    /**
     * Posts one offline sale, or answers why it must be held. Idempotent on the sale's key: a sale an
     * earlier sync already posted comes back ALREADY_POSTED. A rule offline selling never permits
     * returns a hold reason WITHOUT writing anything; an unexpected refusal from the invoice service
     * rolls this transaction back and the orchestrator holds the sale.
     *
     * @param sessionId the server shift the sale belongs to (resolved by the orchestrator)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IngestOutcome ingest(PosTill till, OfflineSale sale, Long sessionId) {
        Long companyId = till.getCompanyId();

        // 1 — Already posted by an earlier sync? Answer it exactly as the online path does.
        var marker = idempotency.findByCompanyIdAndIdemKey(companyId, sale.idempotencyKey());
        if (marker.isPresent() && marker.get().getInvoiceUid() != null) {
            SalesInvoiceDto posted = invoiceService.getByUid(marker.get().getInvoiceUid());
            return IngestOutcome.done(new SaleResult(sale.offlineNumber(), sale.idempotencyKey(),
                    "ALREADY_POSTED", posted.uid(), posted.invoiceNumber(), List.of(), null));
        }

        // 2 — Rules offline selling never permits: hold, post nothing.
        String refusal = offlineRuleViolation(till, sale, sessionId);
        if (refusal != null) {
            return IngestOutcome.hold(refusal, sessionId);
        }
        if (marker.isEmpty() && idempotency.tryReserve(companyId, sale.idempotencyKey()) == 0) {
            // Another request is posting this very sale right now; the till keeps it and retries.
            throw new InFlightException();
        }

        // 3 — The invoice, as the till sold it.
        var customer = customers.findByCompanyIdAndId(companyId, sale.customerId())
                .orElseThrow(() -> new NotFoundException("Customer not found."));
        var company = companies.findScopedById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found."));
        var draft = invoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), customer.getUid(), null, sale.currency(), sale.notes(), null, null));
        SalesInvoice invoice = invoices.findByUid(draft.uid())
                .orElseThrow(() -> new NotFoundException("Invoice not found."));
        invoice.setOrigin(DocumentOrigin.POS);
        invoice.setPosSessionId(sessionId);
        invoice.markSoldOffline(sale.offlineNumber(), sale.soldAt(), Instant.now());
        invoices.saveAndFlush(invoice);

        List<String> priceNotes = new ArrayList<>();
        Map<Long, String[]> stockable = new LinkedHashMap<>();   // productId -> {uid, name}
        for (var line : sale.lines()) {
            var product = products.findByCompanyIdAndId(companyId, line.productId())
                    .orElseThrow(() -> new NotFoundException("Product not found."));
            var unit = units.findByCompanyIdAndId(companyId, line.unitId())
                    .orElseThrow(() -> new NotFoundException("Unit not found."));
            SalesInvoiceLineDto added = invoiceService.addOfflineLine(draft.uid(),
                    new AddInvoiceLineRequest(product.getUid(), unit.getUid(), line.quantity(),
                            null, null, null, line.unitPrice()),
                    Boolean.TRUE.equals(line.priceInclusive()));
            if (added.priceOverridden()) {
                priceNotes.add(product.getName() + ": sold at " + line.unitPrice().toPlainString()
                        + ", price now " + added.listPriceAmount().toPlainString());
            }
            if (product.isStockable()) {
                stockable.put(product.getId(), new String[] {product.getUid(), product.getName()});
            }
        }

        for (var t : sale.tenders()) {
            String ref = t.reference();
            invoiceService.addPayment(draft.uid(), new AddPaymentRequest(
                    t.tenderType(), t.amount(), sale.currency(), ref, null, null,
                    t.tenderType() == TenderType.MOBILE_MONEY ? ref : null,
                    t.tenderType() == TenderType.CARD ? ref : null));
        }

        // 4 — Finalise at the time it was SOLD; stock claimed, never refused (see finalise).
        invoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        idempotency.stampInvoiceUid(companyId, sale.idempotencyKey(), draft.uid());
        SalesInvoiceDto posted = invoiceService.getByUid(draft.uid());

        // 5 — What it broke goes on the manager's review list.
        Set<String> flags = new LinkedHashSet<>();
        Long actor = invoice.getCreatedBy();
        if (!priceNotes.isEmpty()) {
            flags.add(OfflineReviewKind.PRICE_DIFFERENCE.name());
            reviews.save(PosOfflineReview.flagged(companyId, till.getBranchId(), till.getId(),
                    sessionId, invoice.getId(), sale.offlineNumber(),
                    OfflineReviewKind.PRICE_DIFFERENCE, String.join("; ", priceNotes), actor));
        }
        List<String> shortNotes = new ArrayList<>();
        for (var e : stockable.entrySet()) {
            BigDecimal short_ = stockGuard.shortfallAfterClaim(companyId, till.getBranchId(),
                    e.getKey(), e.getValue()[0], true);
            if (short_.signum() > 0) {
                shortNotes.add(e.getValue()[1] + ": " + short_.stripTrailingZeros().toPlainString()
                        + " below zero");
            }
        }
        if (!shortNotes.isEmpty()) {
            flags.add(OfflineReviewKind.NEGATIVE_STOCK.name());
            reviews.save(PosOfflineReview.flagged(companyId, till.getBranchId(), till.getId(),
                    sessionId, invoice.getId(), sale.offlineNumber(),
                    OfflineReviewKind.NEGATIVE_STOCK, String.join("; ", shortNotes), actor));
        }
        List<String> belowCost = belowCostGuard.offlineBelowCostDetails(companyId, till.getBranchId(),
                invoiceLines.findByInvoiceIdOrderByLineNo(invoice.getId()).stream()
                        .map(l -> new BelowCostGuard.PricedLine(l.getProductId(), l.getProductName(),
                                l.getNetAmount(), l.getQtyInBase()))
                        .toList());
        if (!belowCost.isEmpty()) {
            flags.add(OfflineReviewKind.BELOW_COST.name());
            reviews.save(PosOfflineReview.flagged(companyId, till.getBranchId(), till.getId(),
                    sessionId, invoice.getId(), sale.offlineNumber(),
                    OfflineReviewKind.BELOW_COST, String.join("; ", belowCost), actor));
        }
        LocalDate soldOn = calendar.dateOf(companyId, sale.soldAt());
        if (isClosedPeriod(companyId, soldOn)) {
            flags.add(OfflineReviewKind.CLOSED_PERIOD.name());
            reviews.save(PosOfflineReview.flagged(companyId, till.getBranchId(), till.getId(),
                    sessionId, invoice.getId(), sale.offlineNumber(),
                    OfflineReviewKind.CLOSED_PERIOD,
                    "Sold on " + soldOn + ", in a closed accounting period. The posting waits in the"
                    + " GL posting exceptions until the period is reopened.", actor));
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("tillUid", till.getUid());
        detail.put("offlineNumber", sale.offlineNumber());
        detail.put("soldAt", sale.soldAt().toString());
        detail.put("gross", posted.grossTotalAmount().toPlainString());
        detail.put("tillGross", sale.grossTotal().toPlainString());
        detail.put("flags", String.join(",", flags));
        audit.record(AuditEvent.of(AuditActions.POS_OFFLINE_SALE_SYNC, "sales_invoices",
                invoice.getId(), invoice.getUid()).detail(detail));

        return IngestOutcome.done(new SaleResult(sale.offlineNumber(), sale.idempotencyKey(),
                "POSTED", posted.uid(), posted.invoiceNumber(), List.copyOf(flags), null));
    }

    /**
     * Keeps a sale that cannot be posted on the review list, exactly as rung. Idempotent per offline
     * number, so a repeated sync does not list it twice.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SaleResult hold(PosTill till, OfflineSale sale, Long sessionId, String reason,
                           String saleAsJson, Long actorId) {
        if (!reviews.existsByCompanyIdAndOfflineNumberAndKind(
                till.getCompanyId(), sale.offlineNumber(), OfflineReviewKind.HELD)) {
            PosOfflineReview held = reviews.save(PosOfflineReview.held(till.getCompanyId(),
                    till.getBranchId(), till.getId(), sessionId, sale.offlineNumber(), reason,
                    saleAsJson, actorId));
            audit.record(AuditEvent.of(AuditActions.POS_OFFLINE_SALE_HELD, "pos_offline_reviews",
                            held.getId(), held.getUid())
                    .detail(Map.of("tillUid", till.getUid(), "offlineNumber", sale.offlineNumber(),
                            "reason", reason)));
        }
        return new SaleResult(sale.offlineNumber(), sale.idempotencyKey(), "HELD", null, null,
                List.of(OfflineReviewKind.HELD.name()), reason);
    }

    // -------------------------------------------------------------------------
    // Offline periods (D-1, D-8)
    // -------------------------------------------------------------------------

    /** Records or updates one offline period of the till, idempotently on its client key. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordPeriod(PosTill till, OfflinePeriod p, Long switchedBy, Long sessionId,
                             Long actorId) {
        PosOfflinePeriod period = periods.findByCompanyIdAndClientKey(till.getCompanyId(), p.clientKey())
                .orElse(null);
        if (period == null) {
            period = new PosOfflinePeriod(till.getCompanyId(), till.getBranchId(), till.getId(),
                    sessionId, p.clientKey(), p.reason(), switchedBy, p.startedAt(), actorId);
            audit.record(AuditEvent.of(AuditActions.POS_OFFLINE_SWITCH, "pos_tills",
                            till.getId(), till.getUid())
                    .detail(Map.of("clientKey", p.clientKey(), "reason", p.reason().name(),
                            "startedAt", p.startedAt().toString())));
        } else if (!period.getPosTillId().equals(till.getId())) {
            throw new IllegalStateException("That offline period belongs to a different till.");
        }
        period.applyUpdate(p.endedAt(), p.salesCount(), actorId);
        periods.save(period);
    }

    // -------------------------------------------------------------------------

    /** Why this sale can never be posted as an offline sale, or null when it may be. */
    private String offlineRuleViolation(PosTill till, OfflineSale sale, Long sessionId) {
        if (!till.isOfflineAllowed()) {
            return "This till is not allowed to sell offline, so its offline sales were not posted.";
        }
        if (sessionId == null) {
            return "The shift this sale was rung in could not be found or created.";
        }
        PosSession session = sessions.findByCompanyIdAndId(till.getCompanyId(), sessionId).orElse(null);
        if (session == null || !session.getPosTillId().equals(till.getId())) {
            return "The shift this sale was rung in belongs to a different till.";
        }
        if (session.getStatus() != PosSessionStatus.OPEN) {
            return "The shift this sale was rung in was already closed on the server.";
        }
        if (sale.soldAt().isAfter(Instant.now().plus(CLOCK_SKEW))) {
            return "The sale's time is in the future; check the till's clock.";
        }
        var customer = customers.findByCompanyIdAndId(till.getCompanyId(), sale.customerId());
        if (customer.isEmpty() || customer.get().getCustomerKind() != CustomerKind.CASH_WALK_IN) {
            return "Offline sales must be to the walk-in customer.";
        }
        for (var t : sale.tenders()) {
            if (!OFFLINE_TENDERS.contains(t.tenderType())) {
                return "Offline sales can only be paid in cash, mobile money or card.";
            }
            if (t.tenderType() != TenderType.CASH
                    && (t.reference() == null || t.reference().isBlank())) {
                return "A mobile money or card payment needs its reference.";
            }
        }
        for (var line : sale.lines()) {
            var product = products.findByCompanyIdAndId(till.getCompanyId(), line.productId());
            if (product.isEmpty()) {
                return "A product on this sale no longer exists.";
            }
            if (product.get().getRestrictedKind().isRestricted()
                    && !Boolean.TRUE.equals(sale.ageVerified())) {
                return "An age-restricted item was sold without the age check being confirmed.";
            }
        }
        return null;
    }

    private boolean isClosedPeriod(Long companyId, LocalDate day) {
        for (FiscalPeriodDto p : fiscalCalendar.listPeriods(companyId)) {
            if (!day.isBefore(p.startDate()) && !day.isAfter(p.endDate())) {
                return p.status() == PeriodStatus.CLOSED;
            }
        }
        return false;
    }
}
