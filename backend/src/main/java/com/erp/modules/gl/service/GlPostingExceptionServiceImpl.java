package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.GlPostingExceptionDto;
import com.erp.modules.gl.domain.dto.GlPostingRepostResultDto;
import com.erp.modules.gl.domain.dto.GlPostingRetry;
import com.erp.modules.gl.domain.dto.GlSalesTieOutDto;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.GlPostingFailureKind;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.GlConfigRepository;
import com.erp.modules.gl.repository.JournalBatchRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.sales.domain.dto.VatOutputSummaryDto;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditLog;
import com.erp.platform.audit.AuditOpenItemsQuery;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.time.CompanyCalendar;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists and re-posts GL posting exceptions (ACC-02). An exception is a {@code GL.POSTING.FAILED}
 * audit row; resolving it appends a {@code GL.POSTING.RESOLVED} row with the same target uid — the
 * failure row is never edited (audit_logs is append-only).
 *
 * <p><strong>Exactly once.</strong> A re-post (1) takes a transaction-scoped advisory lock on the
 * exception, so concurrent clicks serialise; (2) refuses an exception already resolved; (3) counts
 * the journals of the same source document and source type — if the count has grown since the
 * failure was recorded, something already posted it, so the exception is closed as
 * {@code ALREADY_POSTED} and NOTHING is posted; (4) only then re-invokes the original poster. If
 * the final commit fails after the poster's own REQUIRES_NEW transaction committed, the next
 * attempt lands in step (3) — never a second journal.
 */
@Service
@Transactional
public class GlPostingExceptionServiceImpl implements GlPostingExceptionService {

    private static final Logger log = LoggerFactory.getLogger(GlPostingExceptionServiceImpl.class);

    static final String TARGET   = GlPostingFailureRecorder.TARGET_TYPE;
    static final String OPEN     = "OPEN";
    static final String RESOLVED = "RESOLVED";
    static final String REPOSTED = "REPOSTED";
    static final String ALREADY_POSTED = "ALREADY_POSTED";

    private final AuditOpenItemsQuery openItems;
    private final AuditService audit;
    private final JournalEntryRepository entries;
    private final JournalBatchRepository batches;
    private final GlPostingFailureRecorder recorder;
    private final List<GlPostingRetryHandler> handlers;
    private final ScopeGuard scopeGuard;
    private final ObjectReader reader;
    private final SalesInvoiceService salesInvoices;
    private final GlConfigRepository glConfigs;
    private final JournalLineRepository lines;
    private final CompanyCalendar calendar;

    public GlPostingExceptionServiceImpl(AuditOpenItemsQuery openItems, AuditService audit,
                                         JournalEntryRepository entries,
                                         JournalBatchRepository batches,
                                         GlPostingFailureRecorder recorder,
                                         List<GlPostingRetryHandler> handlers,
                                         ScopeGuard scopeGuard, ObjectMapper mapper,
                                         SalesInvoiceService salesInvoices,
                                         GlConfigRepository glConfigs,
                                         JournalLineRepository lines,
                                         CompanyCalendar calendar) {
        this.calendar = calendar;
        this.salesInvoices = salesInvoices;
        this.glConfigs  = glConfigs;
        this.lines      = lines;
        this.openItems  = openItems;
        this.audit      = audit;
        this.entries    = entries;
        this.batches    = batches;
        this.recorder   = recorder;
        this.handlers   = handlers;
        this.scopeGuard = scopeGuard;
        // Amounts must come back exactly as recorded: without the feature a JSON 1234.5678 is read
        // as a double, and without exact nodes 100.00 is normalised to 1E+2 — a negative scale the
        // sale poster's balancing plug then rounds to, turning a 118 plug into 100.
        this.reader     = mapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .with(JsonNodeFactory.withExactBigDecimals(true));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<GlPostingExceptionDto> list(Long companyId, String sourceType, LocalDate from,
                                            LocalDate to, boolean includeResolved,
                                            Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Page<AuditLog> page = openItems.findOpenItems(companyId, TARGET,
                AuditActions.GL_POSTING_FAILED, AuditActions.GL_POSTING_RESOLVED,
                sourceType, from != null ? from.toString() : null,
                to != null ? to.toString() : null, includeResolved, pageable);

        Map<String, AuditLog> resolutions = includeResolved
                ? openItems.findByTargets(companyId, TARGET, AuditActions.GL_POSTING_RESOLVED,
                        page.getContent().stream().map(AuditLog::getTargetUid).toList())
                        .stream()
                        .collect(Collectors.toMap(AuditLog::getTargetUid, Function.identity(),
                                (a, b) -> a))
                : Map.of();
        return page.map(row -> toDto(row, resolutions.get(row.getTargetUid())));
    }

    @Override
    public GlPostingRepostResultDto repost(Long companyId, String exceptionUid,
                                           LocalDate postingDate) {
        RequestContext.Principal caller = RequestContext.get();
        scopeGuard.assertCanActIn(caller, companyId);
        if (exceptionUid == null || exceptionUid.isBlank()) {
            throw new NotFoundException("Posting exception not found.");
        }

        // (1) serialise concurrent re-posts of the same exception
        openItems.lockItem(TARGET, exceptionUid);

        AuditLog failed = openItems.findByTargets(companyId, TARGET,
                        AuditActions.GL_POSTING_FAILED, List.of(exceptionUid))
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Posting exception not found."));

        // (2) already closed?
        if (!openItems.findByTargets(companyId, TARGET, AuditActions.GL_POSTING_RESOLVED,
                List.of(exceptionUid)).isEmpty()) {
            throw new ConflictException("This posting exception has already been resolved.");
        }

        JsonNode d = parse(failed.getDetail());
        GlPostingFailureKind kind = enumOrNull(GlPostingFailureKind.class, text(d, "kind"));
        JournalSourceType sourceType = enumOrNull(JournalSourceType.class, text(d, "sourceType"));
        String sourceRef = text(d, "sourceRef");
        LocalDate originalDate = dateOrNull(text(d, "postingDate"));
        LocalDate effectiveDate = postingDate != null ? postingDate : originalDate;
        if (kind == null || effectiveDate == null) {
            throw new ConflictException("This posting exception cannot be re-posted automatically. "
                    + "Post a manual journal for it instead.");
        }

        // (3) idempotency: has a journal for this source appeared since the failure?
        if (sourceType != null && sourceRef != null) {
            long atFailure = d.path("journalsAtFailure").asLong(0L);
            long now = entries.countByCompanyIdAndSourceTypeAndSourceRef(
                    companyId, sourceType, sourceRef);
            if (now > atFailure) {
                JournalEntry existing = entries
                        .findFirstByCompanyIdAndSourceTypeAndSourceRefOrderByIdDesc(
                                companyId, sourceType, sourceRef)
                        .orElse(null);
                return resolve(companyId, failed, caller, ALREADY_POSTED, existing);
            }
        }

        // (4) re-invoke the original poster, capturing (not re-recording) a repeat failure
        GlPostingRetryHandler handler = handlers.stream()
                .filter(h -> h.supports(kind))
                .findFirst()
                .orElseThrow(() -> new ConflictException("This posting exception cannot be "
                        + "re-posted automatically. Post a manual journal for it instead."));
        GlPostingRetry retry = new GlPostingRetry(kind, companyId, failed.getBranchId(),
                sourceType, sourceRef, text(d, "documentNumber"), effectiveDate, d.path("args"));

        String[] postedUid = new String[1];
        GlPostingFailureRecorder.Capture capture =
                recorder.capturing(() -> postedUid[0] = handler.repost(retry));
        String reason = capture.reason();
        if (capture.error() != null) {
            if (capture.error() instanceof ConflictException conflict && reason == null) {
                throw conflict;   // a handler's own refusal (e.g. the sale is not posted yet)
            }
            // The poster's REQUIRES_NEW transaction rolled back (e.g. UnexpectedRollback after the
            // poster swallowed); prefer the reason it captured, else classify the exception.
            log.warn("Re-post of GL posting exception {} failed: {}", exceptionUid,
                    capture.error().getMessage());
            postedUid[0] = null;
            if (reason == null) {
                reason = GlPostingFailureRecorder.userSafeReason(capture.error());
            }
        }
        if (postedUid[0] == null) {
            throw new ConflictException("The posting still fails: "
                    + (reason != null ? reason : "the ledger did not accept it.")
                    + " Fix the cause, then re-post again.");
        }
        JournalEntry posted = entries.findByCompanyIdAndUid(companyId, postedUid[0]).orElse(null);
        return resolve(companyId, failed, caller, REPOSTED, posted);
    }

    @Override
    @Transactional(readOnly = true)
    public GlSalesTieOutDto salesTieOut(Long companyId, LocalDate from, LocalDate to) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        LocalDate today = calendar.today(companyId);
        LocalDate start = from != null ? from : today.withDayOfMonth(1);
        LocalDate end   = to != null ? to : start.withDayOfMonth(start.lengthOfMonth());
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("The end date must not be before the start date.");
        }

        // Sales side = invoices finalised in the period MINUS invoices voided in the period — the
        // same netting the VAT return uses. findVatSummaryForPeriod keeps an invoice that was voided
        // later, while the GL nets it out through its SALES_REVERSAL journal; without subtracting the
        // voids every voided sale showed up as "missing revenue" (seen on a restored client DB).
        VatOutputSummaryDto sales  = salesInvoices.findVatSummaryForPeriod(companyId, start, end);
        VatOutputSummaryDto voided = salesInvoices.findVatVoidSummaryForPeriod(companyId, start, end);
        BigDecimal salesVat = outputVat(sales).subtract(outputVat(voided));
        BigDecimal salesNet = taxableBase(sales).subtract(taxableBase(voided));

        Long revenueAcct = glConfigs.findByCompanyIdAndConfigKey(companyId, GlConfigKey.SALES_REVENUE)
                .map(c -> c.getAccountId()).orElse(null);
        Long vatAcct = glConfigs.findByCompanyIdAndConfigKey(companyId, GlConfigKey.VAT_PAYABLE)
                .map(c -> c.getAccountId()).orElse(null);
        BigDecimal glRevenue = BigDecimal.ZERO;
        BigDecimal glVat = BigDecimal.ZERO;
        for (Object[] row : lines.periodMovementByAccountForSources(companyId, start, end,
                List.of(JournalSourceType.SALES, JournalSourceType.SALES_REVERSAL))) {
            Long accountId = ((Number) row[0]).longValue();
            BigDecimal netCredit = nz((BigDecimal) row[2]).subtract(nz((BigDecimal) row[1]));
            if (accountId.equals(revenueAcct)) {
                glRevenue = glRevenue.add(netCredit);
            } else if (accountId.equals(vatAcct)) {
                glVat = glVat.add(netCredit);
            }
        }
        return new GlSalesTieOutDto(start, end, salesNet, salesVat, glRevenue, glVat,
                salesNet.subtract(glRevenue), salesVat.subtract(glVat));
    }

    private static BigDecimal outputVat(VatOutputSummaryDto s) {
        return nz(s != null ? s.totalOutputVat() : null);
    }

    private static BigDecimal taxableBase(VatOutputSummaryDto s) {
        return s == null || s.byBand() == null ? BigDecimal.ZERO
                : s.byBand().values().stream()
                        .map(b -> nz(b.taxableBase()))
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    // -------------------------------------------------------------------------

    private GlPostingRepostResultDto resolve(Long companyId, AuditLog failed,
                                             RequestContext.Principal caller, String outcome,
                                             JournalEntry journal) {
        String batchNumber = batchNumberOf(companyId, journal);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("outcome", outcome);
        detail.put("journalEntryUid", journal != null ? journal.getUid() : null);
        detail.put("batchNumber", batchNumber);
        detail.put("postingDate", journal != null && journal.getPostingDate() != null
                ? journal.getPostingDate().toString() : null);
        detail.put("resolvedBy", caller != null ? caller.username() : null);
        audit.recordInScope(
                AuditEvent.of(AuditActions.GL_POSTING_RESOLVED, TARGET, null,
                                failed.getTargetUid())
                        .detail(detail),
                companyId, failed.getBranchId());
        return new GlPostingRepostResultDto(failed.getTargetUid(), outcome,
                journal != null ? journal.getUid() : null, batchNumber,
                journal != null ? journal.getPostingDate() : null);
    }

    private String batchNumberOf(Long companyId, JournalEntry journal) {
        if (journal == null) {
            return null;
        }
        return batches.findByIdAndCompanyId(journal.getBatchId(), companyId)
                .map(b -> b.getBatchNumber())
                .orElse(null);
    }

    private GlPostingExceptionDto toDto(AuditLog row, AuditLog resolution) {
        JsonNode d = parse(row.getDetail());
        JsonNode r = resolution != null ? parse(resolution.getDetail()) : MissingNode.getInstance();
        String amount = text(d, "amount");
        return new GlPostingExceptionDto(
                row.getTargetUid(),
                text(d, "kind"),
                text(d, "sourceType"),
                text(d, "sourceRef"),
                text(d, "documentNumber"),
                dateOrNull(text(d, "postingDate")),
                amount != null ? new BigDecimal(amount) : null,
                text(d, "reason"),
                row.getAt(),
                resolution != null ? RESOLVED : OPEN,
                resolution != null ? resolution.getAt() : null,
                text(r, "resolvedBy"),
                text(r, "outcome"),
                text(r, "journalEntryUid"),
                text(r, "batchNumber"));
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return MissingNode.getInstance();
        }
        try {
            return reader.readTree(json);
        } catch (Exception ex) {
            log.warn("Unreadable GL posting exception detail: {}", ex.getMessage());
            return MissingNode.getInstance();
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode n = node.path(key);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static LocalDate dateOrNull(String s) {
        try {
            return s == null || s.isBlank() ? null : LocalDate.parse(s);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String name) {
        if (name == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
