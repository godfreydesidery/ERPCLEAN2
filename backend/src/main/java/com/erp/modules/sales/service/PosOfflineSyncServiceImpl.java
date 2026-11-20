package com.erp.modules.sales.service;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.sales.domain.dto.AcknowledgeOfflineReviewRequest;
import com.erp.modules.sales.domain.dto.OfflineSyncRequest;
import com.erp.modules.sales.domain.dto.OfflineSyncRequest.OfflineSale;
import com.erp.modules.sales.domain.dto.OfflineSyncResultDto;
import com.erp.modules.sales.domain.dto.OfflineSyncResultDto.SaleResult;
import com.erp.modules.sales.domain.dto.OfflineSyncResultDto.SessionResult;
import com.erp.modules.sales.domain.dto.PosOfflinePeriodDto;
import com.erp.modules.sales.domain.dto.PosOfflineReviewDto;
import com.erp.modules.sales.domain.entity.PosOfflinePeriod;
import com.erp.modules.sales.domain.entity.PosOfflineReview;
import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.entity.PosTill;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.OfflineReviewStatus;
import com.erp.modules.sales.domain.exception.PosSaleFlowException;
import com.erp.modules.sales.repository.PosOfflinePeriodRepository;
import com.erp.modules.sales.repository.PosOfflineReviewRepository;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.PosTillRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Offline POS sync and review list (ADR-0068 D-4, D-5, D-8).
 *
 * <p>{@link #sync} deliberately runs WITHOUT a surrounding transaction: every shift, sale and period
 * is written by {@link PosOfflineSaleIngestor} in its own transaction, so a sale that cannot be posted
 * is held on its own and never rolls back the sales that could.
 */
@Service
public class PosOfflineSyncServiceImpl implements PosOfflineSyncService {

    private static final Logger log = LoggerFactory.getLogger(PosOfflineSyncServiceImpl.class);
    private static final int MAX_PAGE_SIZE = 100;

    private final PosTillRepository tills;
    private final PosSessionRepository sessions;
    private final PosOfflineReviewRepository reviews;
    private final PosOfflinePeriodRepository periods;
    private final SalesInvoiceRepository invoices;
    private final AppUserRepository users;
    private final PosOfflineSaleIngestor ingestor;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;
    private final ObjectMapper json;
    /** ADR-0069 D-6: which offline sales a read-only tenant may still sync. */
    private final com.erp.modules.subscription.service.SubscriptionGate subscriptionGate;

    static final String SOLD_WHILE_READ_ONLY = "Sold after the system became read-only for an "
            + "overdue subscription.";

    public PosOfflineSyncServiceImpl(PosTillRepository tills, PosSessionRepository sessions,
                                     PosOfflineReviewRepository reviews,
                                     PosOfflinePeriodRepository periods,
                                     SalesInvoiceRepository invoices, AppUserRepository users,
                                     PosOfflineSaleIngestor ingestor, ScopeGuard scopeGuard,
                                     AuditService audit, ObjectMapper json,
                                     com.erp.modules.subscription.service.SubscriptionGate subscriptionGate) {
        this.subscriptionGate = subscriptionGate;
        this.tills = tills;
        this.sessions = sessions;
        this.reviews = reviews;
        this.periods = periods;
        this.invoices = invoices;
        this.users = users;
        this.ingestor = ingestor;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.json = json;
    }

    // -------------------------------------------------------------------------
    // Sync (D-4, D-5)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public OfflineSyncResultDto sync(OfflineSyncRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        PosTill till = tills.findByUid(req.tillUid())
                .orElseThrow(() -> NotFoundException.of("PosTill", req.tillUid()));
        scopeGuard.assertCanActIn(principal, till.getCompanyId());
        // The invoices land in the caller's active branch, so it must be the till's own.
        if (principal != null && principal.branchId() != null
                && !principal.branchId().equals(till.getBranchId())) {
            throw new ForbiddenException(
                    "Switch to this till's branch before sending its offline sales.");
        }
        Long actorId = principal == null ? null : principal.userId();

        // 1 — Shifts opened offline. A key that fails stays unresolved; its sales are then held.
        List<SessionResult> sessionResults = new ArrayList<>();
        Map<String, Long> sessionIdByKey = new HashMap<>();
        for (var s : nullSafe(req.sessions())) {
            try {
                SessionResult r = ingestor.ensureSession(till, s, actorId);
                sessionResults.add(r);
                sessions.findByUid(r.sessionUid()).ifPresent(x -> sessionIdByKey.put(s.clientKey(), x.getId()));
            } catch (RuntimeException ex) {
                log.warn("Offline shift {} of till {} could not be created: {}",
                        s.clientKey(), till.getUid(), ex.getMessage());
            }
        }

        // 2 — Sales, each on its own. ADR-0069 D-6: a tenant that has gone read-only for an overdue
        // subscription still gets the sales its tills made BEFORE that moment - they really
        // happened while it was allowed to sell. Later ones are held (kept, not lost) for review.
        java.time.Instant readOnlySince = principal == null ? null
                : subscriptionGate.readOnlySince(principal.organisationId());
        List<SaleResult> saleResults = new ArrayList<>();
        for (OfflineSale sale : nullSafe(req.sales())) {
            Long sessionId = resolveSessionId(till, sale.sessionUid(), sale.sessionClientKey(),
                    sessionIdByKey);
            if (readOnlySince != null && !sale.soldAt().isBefore(readOnlySince)) {
                saleResults.add(ingestor.hold(till, sale, sessionId, SOLD_WHILE_READ_ONLY,
                        asJson(sale), actorId));
                continue;
            }
            try {
                var outcome = ingestor.ingest(till, sale, sessionId);
                saleResults.add(outcome.result() != null ? outcome.result()
                        : ingestor.hold(till, sale, outcome.sessionId(), outcome.holdReason(),
                                asJson(sale), actorId));
            } catch (PosOfflineSaleIngestor.InFlightException ex) {
                // Left out of the answer on purpose: the till keeps it and sends it again.
                log.info("Offline sale {} of till {} is in flight; till will retry",
                        sale.offlineNumber(), till.getUid());
            } catch (RuntimeException ex) {
                String reason = friendlyReason(ex);
                if (!isBusinessRefusal(ex)) {
                    log.error("Offline sale {} of till {} could not be posted",
                            sale.offlineNumber(), till.getUid(), ex);
                }
                saleResults.add(ingestor.hold(till, sale, sessionId, reason, asJson(sale), actorId));
            }
        }

        // 3 — Offline periods (history; one bad row never blocks the rest).
        int recorded = 0;
        for (var p : nullSafe(req.periods())) {
            try {
                Long switchedBy = resolveSwitcher(p.switchedByUid(), actorId);
                Long sessionId = resolveSessionId(till, p.sessionUid(), p.sessionClientKey(),
                        sessionIdByKey);
                ingestor.recordPeriod(till, p, switchedBy, sessionId, actorId);
                recorded++;
            } catch (RuntimeException ex) {
                log.warn("Offline period {} of till {} could not be recorded: {}",
                        p.clientKey(), till.getUid(), ex.getMessage());
            }
        }
        return new OfflineSyncResultDto(sessionResults, saleResults, recorded);
    }

    // -------------------------------------------------------------------------
    // Review list (D-8)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<PosOfflineReviewDto> listReviews(OfflineReviewStatus status, int page, int size) {
        RequestContext.Principal p = requireBranch();
        Page<PosOfflineReview> rows = reviews.findByCompanyIdAndBranchIdAndStatusOrderByCreatedAtDesc(
                p.companyId(), p.branchId(), status == null ? OfflineReviewStatus.OPEN : status,
                PageRequest.of(Math.max(page, 0), clamp(size)));
        Map<Long, String> tillNames = tillNames(p.companyId(),
                rows.map(PosOfflineReview::getPosTillId).toList());
        Map<Long, SalesInvoice> invoiceById = invoices.findAllById(rows.stream()
                        .map(PosOfflineReview::getSalesInvoiceId).filter(Objects::nonNull).toList())
                .stream()
                .filter(i -> i.getCompanyId().equals(p.companyId()))
                .collect(Collectors.toMap(SalesInvoice::getId, Function.identity()));
        Map<Long, String> userNames = userNames(rows.map(PosOfflineReview::getAcknowledgedBy).toList());
        return rows.map(r -> toDto(r, tillNames, invoiceById, userNames));
    }

    @Override
    @Transactional
    public PosOfflineReviewDto acknowledge(String reviewUid, AcknowledgeOfflineReviewRequest request) {
        RequestContext.Principal p = requireBranch();
        PosOfflineReview review = reviews.findByUid(reviewUid)
                .orElseThrow(() -> NotFoundException.of("PosOfflineReview", reviewUid));
        scopeGuard.assertCanActIn(p, review.getCompanyId());
        if (!review.getBranchId().equals(p.branchId())) {
            throw new NotFoundException("That review item was not found in this branch.");
        }
        try {
            review.acknowledge(p.userId(), request == null ? null : request.note());
        } catch (IllegalStateException ex) {
            throw new ConflictException(ex.getMessage());
        }
        reviews.save(review);
        audit.record(AuditEvent.of(AuditActions.POS_OFFLINE_REVIEW_ACK, "pos_offline_reviews",
                review.getId(), review.getUid())
                .detail(Map.of("kind", review.getKind().name(),
                        "offlineNumber", review.getOfflineNumber() == null ? "" : review.getOfflineNumber())));
        Map<Long, SalesInvoice> invoiceById = new HashMap<>();
        if (review.getSalesInvoiceId() != null) {
            invoices.findAllById(List.of(review.getSalesInvoiceId())).stream()
                    .filter(i -> i.getCompanyId().equals(p.companyId()))
                    .forEach(i -> invoiceById.put(i.getId(), i));
        }
        return toDto(review, tillNames(p.companyId(), List.of(review.getPosTillId())), invoiceById,
                userNames(List.of(p.userId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PosOfflinePeriodDto> listPeriods(int page, int size) {
        RequestContext.Principal p = requireBranch();
        Page<PosOfflinePeriod> rows = periods.findByCompanyIdAndBranchIdOrderByStartedAtDesc(
                p.companyId(), p.branchId(), PageRequest.of(Math.max(page, 0), clamp(size)));
        Map<Long, String> tillNames = tillNames(p.companyId(),
                rows.map(PosOfflinePeriod::getPosTillId).toList());
        Map<Long, String> userNames = userNames(rows.map(PosOfflinePeriod::getSwitchedBy).toList());
        return rows.map(r -> new PosOfflinePeriodDto(r.getUid(), tillNames.get(r.getPosTillId()),
                r.getReason(), userNames.get(r.getSwitchedBy()), r.getStartedAt(), r.getEndedAt(),
                r.getSalesCount()));
    }

    // -------------------------------------------------------------------------

    private Long resolveSessionId(PosTill till, String sessionUid, String clientKey,
                                  Map<String, Long> fromThisBatch) {
        if (sessionUid != null && !sessionUid.isBlank()) {
            return sessions.findByUid(sessionUid)
                    .filter(s -> s.getCompanyId().equals(till.getCompanyId()))
                    .map(PosSession::getId)
                    .orElse(null);
        }
        if (clientKey != null && !clientKey.isBlank()) {
            Long id = fromThisBatch.get(clientKey);
            if (id != null) {
                return id;
            }
            return sessions.findByCompanyIdAndOfflineClientKey(till.getCompanyId(), clientKey)
                    .map(PosSession::getId)
                    .orElse(null);
        }
        return null;
    }

    /** The manager named by the till for a MANUAL switch; otherwise the signed-in user. */
    private Long resolveSwitcher(String uid, Long actorId) {
        if (uid == null || uid.isBlank()) {
            return actorId;
        }
        return users.findByUid(uid).map(AppUser::getId).orElse(actorId);
    }

    private RequestContext.Principal requireBranch() {
        RequestContext.Principal p = RequestContext.get();
        if (p == null || p.companyId() == null || p.branchId() == null) {
            throw new ForbiddenException("Select a branch to see its offline sales.");
        }
        return p;
    }

    private Map<Long, String> tillNames(Long companyId, List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return tills.findByCompanyIdAndIdIn(companyId, ids.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(PosTill::getId, PosTill::getName));
    }

    private Map<Long, String> userNames(List<Long> ids) {
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(wanted).stream()
                .collect(Collectors.toMap(AppUser::getId,
                        u -> u.getDisplayName() == null ? u.getUsername() : u.getDisplayName()));
    }

    private PosOfflineReviewDto toDto(PosOfflineReview r, Map<Long, String> tillNames,
                                      Map<Long, SalesInvoice> invoiceById,
                                      Map<Long, String> userNames) {
        SalesInvoice inv = r.getSalesInvoiceId() == null ? null : invoiceById.get(r.getSalesInvoiceId());
        return new PosOfflineReviewDto(r.getUid(), r.getKind(), r.getStatus(), r.getOfflineNumber(),
                r.getDetail(), tillNames.get(r.getPosTillId()),
                inv == null ? null : inv.getUid(), inv == null ? null : inv.getInvoiceNumber(),
                r.getHeldRequest(), r.getCreatedAt(),
                r.getAcknowledgedBy() == null ? null : userNames.get(r.getAcknowledgedBy()),
                r.getAcknowledgedAt(), r.getAcknowledgeNote());
    }

    private String asJson(OfflineSale sale) {
        try {
            return json.writeValueAsString(sale);
        } catch (JsonProcessingException ex) {
            return "{\"offlineNumber\":\"" + sale.offlineNumber() + "\"}";
        }
    }

    /** Business refusals carry user-safe text by design; anything else gets a plain sentence. */
    private static boolean isBusinessRefusal(RuntimeException ex) {
        return ex instanceof ConflictException || ex instanceof IllegalArgumentException
                || ex instanceof IllegalStateException || ex instanceof PosSaleFlowException
                || ex instanceof NotFoundException;
    }

    private static String friendlyReason(RuntimeException ex) {
        if (ex instanceof NotFoundException) {
            // Its text names internal entity types ("PosTill not found.") — never shown to a cashier.
            return "Not posted: a product, unit or customer on this sale no longer exists.";
        }
        if (isBusinessRefusal(ex) && ex.getMessage() != null && !ex.getMessage().isBlank()) {
            return "Not posted: " + ex.getMessage();
        }
        return "Not posted: the server could not record this sale. A manager can see it on the"
                + " offline review list.";
    }

    private static int clamp(int size) {
        return size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
