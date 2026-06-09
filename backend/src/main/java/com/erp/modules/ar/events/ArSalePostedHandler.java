package com.erp.modules.ar.events;

import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.enums.ArInvoiceSource;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.sales.domain.dto.InvoicePostingTotalsDto;
import com.erp.modules.sales.domain.dto.SaleFinalisedPayload;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code SALE.FINALISED} — for CREDIT_ACCOUNT customers creates an AR open item.
 * Posts NOTHING to GL (the SalesPostingHandler already debited 1200 — BR-AR-02, D-5).
 *
 * <p>Consumer marker {@code AR.SALE_POST} (distinct from GL.SALES_POST / STOCK_*) so it dedupes
 * on its own progress (ADR-0009 D-6, ADR-0014 D-5).
 *
 * <p>Failure isolation: this handler does NOT use GLPostingSafeInvoker (it posts nothing to GL),
 * so its only fallible work is the ar_invoices INSERT. The MANDATORY propagation joins the
 * dispatcher's TX; if this handler throws, the dispatcher's catch logs the anomaly and still
 * calls markProcessed (via the outer finally / anomaly-log pattern) so the event is not
 * re-attempted indefinitely. A DB partial-unique backstop (uq_ar_invoice_source_sale) ensures
 * idempotency even if markProcessed races.
 */
@Component
public class ArSalePostedHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ArSalePostedHandler.class);

    static final String CONSUMER = "AR.SALE_POST";

    private final IdempotencyGuard guard;
    private final SalesInvoiceService salesInvoiceService;
    private final ArInvoiceRepository arInvoices;
    private final CustomerRepository customers;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public ArSalePostedHandler(IdempotencyGuard guard,
                                SalesInvoiceService salesInvoiceService,
                                ArInvoiceRepository arInvoices,
                                CustomerRepository customers,
                                AuditService audit,
                                ObjectMapper objectMapper) {
        this.guard               = guard;
        this.salesInvoiceService = salesInvoiceService;
        this.arInvoices          = arInvoices;
        this.customers           = customers;
        this.audit               = audit;
        this.objectMapper        = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.SALE_FINALISED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        // 1. Primary dedup (ADR-0009 D-6a)
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("ArSalePostedHandler: event uid={} already processed — skipping", event.getUid());
            return;
        }

        SaleFinalisedPayload payload = deserialise(event.getPayload());
        Long companyId = event.getCompanyId();
        Long branchId  = event.getBranchId();

        // 2. System RequestContext (mirrors SalesPostingHandler pattern)
        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, companyId, branchId, null));
        try {
            createOpenItemIfCredit(event, payload, companyId, branchId);
        } catch (Exception ex) {
            // Anomaly — log and fall through to markProcessed so the event is not retried forever
            // and does not poison co-consumers (SalesPostingHandler, SaleIssueStockHandler).
            log.warn("ArSalePostedHandler: open-item creation failed for invoice uid={} company={} — "
                            + "anomaly recorded, marking processed. error={}",
                    payload.invoiceUid(), companyId, ex.getMessage());
        } finally {
            if (previous == null) RequestContext.clear();
            else RequestContext.set(previous);
        }

        // 3. Mark processed in same TX (ADR-0009 D-6a)
        guard.markProcessed(CONSUMER, event.getUid());
    }

    // -------------------------------------------------------------------------

    private void createOpenItemIfCredit(DomainEvent event, SaleFinalisedPayload payload,
                                         Long companyId, Long branchId) {
        // Re-read the invoice totals (never trust payload amounts — mirror SalesPostingHandler)
        InvoicePostingTotalsDto totals = salesInvoiceService
                .findPostingTotalsByUidAndCompany(payload.invoiceUid(), companyId)
                .orElse(null);

        if (totals == null || !"FINALISED".equals(totals.status())) {
            log.warn("ArSalePostedHandler: invoice uid={} not found or not FINALISED in company={} "
                            + "— anomaly. event uid={}", payload.invoiceUid(), companyId, event.getUid());
            return;
        }

        // 3a. Skip cash sales (FR-AR-02, BR-AR-01)
        if (totals.isCashSale()) {
            log.debug("ArSalePostedHandler: invoice uid={} is a cash sale — skipping AR open item",
                    payload.invoiceUid());
            return;
        }

        // 3b. Idempotency backstop: if the open item already exists (partial-unique DB constraint
        //     or a prior race), skip rather than violate the unique index.
        if (arInvoices.findBySalesInvoiceUid(companyId, payload.invoiceUid()).isPresent()) {
            log.debug("ArSalePostedHandler: open item already exists for invoice uid={} — skipping",
                    payload.invoiceUid());
            return;
        }

        // 3c. Resolve due date from customer payment terms (OQ-AR-01: else net-on-receipt)
        LocalDate invoiceDate = totals.finalisedAt() != null
                ? totals.finalisedAt().atZone(ZoneOffset.UTC).toLocalDate()
                : LocalDate.now();

        Customer customer = totals.customerId() != null
                ? customers.findById(totals.customerId()).orElse(null)
                : null;
        int termsDays = (customer != null && customer.getPaymentTermsDays() != null)
                ? customer.getPaymentTermsDays() : 0;
        LocalDate dueDate = invoiceDate.plusDays(termsDays);

        // 3d. Create the open item — NO GL POST (D-5, BR-AR-02)
        // Use outstandingAmount from totals if present; fall back to grossTotalAmount (D-10 v1 default)
        java.math.BigDecimal receivable = totals.outstandingAmount() != null
                ? totals.outstandingAmount()
                : totals.grossTotalAmount();

        ArInvoice inv = new ArInvoice(
                companyId, branchId, totals.customerId(),
                ArInvoiceSource.SALE, payload.invoiceUid(), null,
                receivable, totals.currency(),
                invoiceDate, dueDate, null /* SYSTEM — no user actor */);
        inv = arInvoices.save(inv);

        audit.record(AuditEvent.of(AuditActions.AR_OPENITEM_CREATE, "ar_invoices",
                        inv.getId(), inv.getUid())
                .detail(Map.of(
                        "sourceInvoiceUid", payload.invoiceUid(),
                        "amount", receivable.toPlainString(),
                        "actor", "SYSTEM")));

        log.debug("ArSalePostedHandler: open item uid={} created for invoice uid={} amount={}",
                inv.getUid(), payload.invoiceUid(), receivable);
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
