package com.erp.modules.gl.events;

import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.sales.domain.dto.InvoicePostingTotalsDto;
import com.erp.modules.sales.domain.dto.SaleFinalisedPayload;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code SALE.FINALISED} — posts a balanced GL journal entry for the sale
 * (ADR-0013 D-6, FR-GL-10, BR-GL-09).
 *
 * <p>Entry: DR Cash (v1 cash sale, OQ-GL-02) for gross; CR Sales Revenue for net;
 * CR VAT Payable for VAT. If VAT == 0 the VAT line is omitted (a zero line violates the DB CHECK).
 * Balanced by construction: net + vat == gross (ADR-0008 D-4).
 *
 * <p>Mirrors {@code SaleIssueStockHandler} exactly: @Transactional(MANDATORY), idempotency via
 * IdempotencyGuard, system RequestContext, re-reads invoice by uid (never trusts payload amounts).
 */
@Component
public class SalesPostingHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SalesPostingHandler.class);

    static final String CONSUMER = "GL.SALES_POST";

    private final IdempotencyGuard guard;
    private final SalesInvoiceService salesInvoiceService;
    private final GLPostingSafeInvoker safeInvoker;
    private final ObjectMapper objectMapper;

    public SalesPostingHandler(IdempotencyGuard guard,
                                SalesInvoiceService salesInvoiceService,
                                GLPostingSafeInvoker safeInvoker,
                                ObjectMapper objectMapper) {
        this.guard               = guard;
        this.salesInvoiceService = salesInvoiceService;
        this.safeInvoker         = safeInvoker;
        this.objectMapper        = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.SALE_FINALISED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        // 1. Primary dedup (ADR-0009 D-6a, BR-GL-09)
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("SalesPostingHandler: event uid={} already processed — skipping", event.getUid());
            return;
        }

        // 2. Deserialise payload (only invoiceUid + companyId/branchId used — amounts re-read)
        SaleFinalisedPayload payload = deserialise(event.getPayload());

        // 3. Establish system RequestContext (mirrors SaleIssueStockHandler D-5)
        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(RequestContext.Principal.system(event.getCompanyId(), event.getBranchId()));
        try {
            postSalesEntry(event, payload);
        } catch (Exception ex) {
            // GL config missing, no open fiscal period, or other GL infra anomaly.
            // Log and fall through to markProcessed so the event is not re-attempted
            // indefinitely and does not poison the dispatch TX for other handlers
            // (e.g. SaleIssueStockHandler running in the same dispatch).
            // A WARN is emitted; ops can repair gl_configs and manually post a correction entry.
            log.warn("SalesPostingHandler: GL posting failed for invoice uid={} company={} — "
                            + "anomaly recorded, marking event processed to avoid poison TX. "
                            + "error={} event uid={}",
                    payload.invoiceUid(), event.getCompanyId(), ex.getMessage(), event.getUid());
        } finally {
            if (previous == null) {
                RequestContext.clear();
            } else {
                RequestContext.set(previous);
            }
        }

        // 4. Mark processed in same TX (atomicity, ADR-0009 D-6a)
        guard.markProcessed(CONSUMER, event.getUid());
    }

    private void postSalesEntry(DomainEvent event, SaleFinalisedPayload payload) {
        Long companyId = event.getCompanyId();

        // Re-read the finalised invoice for monetary facts (ADR-0013 D-6; payload has NO amounts)
        InvoicePostingTotalsDto totals = salesInvoiceService
                .findPostingTotalsByUidAndCompany(payload.invoiceUid(), companyId)
                .orElse(null);

        if (totals == null || !"FINALISED".equals(totals.status())) {
            // Out-of-order / anomaly — record and still mark processed (mirrors OQ-STOCK-10)
            log.warn("SalesPostingHandler: SALE.FINALISED for invoice uid={} but invoice not found "
                            + "or not FINALISED in company {} — anomaly recorded. event uid={}",
                    payload.invoiceUid(), companyId, event.getUid());
            return;
        }

        LocalDate postingDate = totals.finalisedAt() != null
                ? totals.finalisedAt().atZone(ZoneOffset.UTC).toLocalDate()
                : LocalDate.now();

        // Resolve accounts AND post inside ONE REQUIRES_NEW TX (ADR-0013 D-6). Account resolution
        // must NOT run in this handler's (dispatchOne's) TX — a missing gl_config there would mark
        // the shared dispatch TX rollback-only and silently roll back the Stock handler. The
        // invoker isolates the whole resolve+post unit and returns null on any GL anomaly.
        // ADR-0025 D-6: pass the invoice's header-level dimension default ids onto the revenue leg.
        // ADR-0033 D-4c: pass the invoice's project tag onto the revenue leg so the project P&L
        // roll-up includes this revenue (re-read from sales_invoices.project_id, not the payload).
        safeInvoker.postSaleInNewTx(
                companyId, event.getBranchId(), payload.invoiceUid(),
                totals.currency(), totals.grossTotalAmount(), totals.netTotalAmount(),
                totals.vatTotalAmount(), totals.isCashSale(), postingDate,
                totals.costCentreValueId(), totals.departmentValueId(),
                totals.projectId(), totals.projectTaskId());
        log.debug("SalesPostingHandler: GL posting attempted for invoice uid={} company={}",
                payload.invoiceUid(), companyId);
    }

    private SaleFinalisedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, SaleFinalisedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise SaleFinalisedPayload: " + ex.getMessage(), ex);
        }
    }
}
