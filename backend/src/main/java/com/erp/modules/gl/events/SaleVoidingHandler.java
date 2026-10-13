package com.erp.modules.gl.events;

import com.erp.modules.gl.domain.dto.GlPostingFailure;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.enums.GlPostingFailureKind;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.gl.service.GlPostingFailureRecorder;
import com.erp.modules.sales.domain.dto.SaleVoidedPayload;
import com.erp.platform.common.time.CompanyCalendar;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code SALE.VOIDED} — finds the original SALES journal entry for the invoice
 * and posts a reversing entry of type {@code SALES_REVERSAL} (ADR-0013 D-6, BR-GL-11,
 * FR-GL-12, FR-GL-13).
 *
 * <p>Out-of-order anomaly (mirrors OQ-STOCK-10 / SaleReversalStockHandler): if no SALES entry
 * exists for the invoice uid, an anomaly WARN is recorded and the event is still marked processed —
 * no phantom reversal is posted.
 *
 * <p>The original entry is found via the partial unique index on
 * {@code journal_entries(company_id, source_type, source_ref) WHERE source_type IN ('SALES',...)}.
 */
// ARC-08: runs before the cash book's handler for the same event, which mirrors the journal this
// handler posts (in its own committed transaction). Unordered handlers keep their relative order.
@org.springframework.core.annotation.Order(0)
@Component
public class SaleVoidingHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SaleVoidingHandler.class);

    static final String CONSUMER = "GL.SALES_VOID";

    private final IdempotencyGuard guard;
    private final JournalEntryRepository journalEntries;
    private final GLPostingSafeInvoker safeInvoker;
    private final ObjectMapper objectMapper;
    private final CompanyCalendar calendar;

    private final GlPostingFailureRecorder failures;

    public SaleVoidingHandler(IdempotencyGuard guard,
                               JournalEntryRepository journalEntries,
                               GLPostingSafeInvoker safeInvoker,
                               ObjectMapper objectMapper,
                               CompanyCalendar calendar,
                               GlPostingFailureRecorder failures) {
        this.guard          = guard;
        this.journalEntries = journalEntries;
        this.safeInvoker    = safeInvoker;
        this.objectMapper   = objectMapper;
        this.calendar       = calendar;
        this.failures       = failures;
    }

    @Override
    public String eventType() {
        return DomainEventType.SALE_VOIDED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("SaleVoidingHandler: event uid={} already processed — skipping", event.getUid());
            return;
        }

        SaleVoidedPayload payload = deserialise(event.getPayload());
        Long companyId = event.getCompanyId();

        // Look up the original SALES journal entry (hits the partial unique index)
        JournalEntry original = journalEntries
                .findByCompanyIdAndSourceTypeAndSourceRef(
                        companyId, JournalSourceType.SALES, payload.invoiceUid())
                .orElse(null);

        if (original == null) {
            // Out-of-order anomaly: SALE.VOIDED before SALE.FINALISED was posted to GL
            log.warn("SaleVoidingHandler: SALE.VOIDED for invoice uid={} but no SALES journal entry "
                            + "found in company {} — anomaly recorded (OQ-GL-03). void event uid={}",
                    payload.invoiceUid(), companyId, event.getUid());
            // ACC-02: list it as a posting exception. Typically the sale's own posting failed
            // (also listed); once that is re-posted, re-posting this one reverses it.
            failures.record(GlPostingFailure.of(GlPostingFailureKind.SALE_VOID, companyId,
                            event.getBranchId(), JournalSourceType.SALES_REVERSAL,
                            payload.invoiceUid(), payload.invoiceNumber(),
                            calendar.today(companyId)),
                    new IllegalStateException("The sale was voided, but the sale itself had not "
                            + "been posted to the ledger, so there was nothing to reverse."));
            // Still mark processed — do not retry (mirrors SaleReversalStockHandler)
            guard.markProcessed(CONSUMER, event.getUid());
            return;
        }

        // Establish system RequestContext for the posting engine's ScopeGuard checks
        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(RequestContext.Principal.system(companyId, event.getBranchId()));
        try {
            LocalDate reversalDate = calendar.today(companyId);
            safeInvoker.postReversalInNewTx(
                    original.getUid(),
                    reversalDate,
                    JournalSourceType.SALES_REVERSAL,
                    payload.invoiceUid(),
                    null   // SYSTEM actor, no userId
            );
            log.debug("SaleVoidingHandler: posted SALES_REVERSAL for invoice uid={} company={}",
                    payload.invoiceUid(), companyId);
        } catch (Exception ex) {
            // Closed period or other GL anomaly — log and fall through to markProcessed.
            // Never poison the dispatch TX for other handlers sharing this REQUIRES_NEW scope.
            log.warn("SaleVoidingHandler: SALES_REVERSAL failed for invoice uid={} company={} — "
                            + "anomaly recorded, marking event processed. error={} event uid={}",
                    payload.invoiceUid(), companyId, ex.getMessage(), event.getUid());
        } finally {
            if (previous == null) {
                RequestContext.clear();
            } else {
                RequestContext.set(previous);
            }
        }

        guard.markProcessed(CONSUMER, event.getUid());
    }

    private SaleVoidedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, SaleVoidedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise SaleVoidedPayload: " + ex.getMessage(), ex);
        }
    }
}
