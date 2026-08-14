package com.erp.modules.ar.events;

import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.enums.ArInvoiceSource;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.entity.PaymentTerms;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import com.erp.modules.parties.service.PaymentTermsDueDateCalculator;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final PaymentTermsRepository paymentTermsRepo;
    private final CompanyRepository companies;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public ArSalePostedHandler(IdempotencyGuard guard,
                                SalesInvoiceService salesInvoiceService,
                                ArInvoiceRepository arInvoices,
                                CustomerRepository customers,
                                PaymentTermsRepository paymentTermsRepo,
                                CompanyRepository companies,
                                AuditService audit,
                                ObjectMapper objectMapper) {
        this.guard               = guard;
        this.salesInvoiceService = salesInvoiceService;
        this.arInvoices          = arInvoices;
        this.customers           = customers;
        this.paymentTermsRepo    = paymentTermsRepo;
        this.companies           = companies;
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
        RequestContext.set(RequestContext.Principal.system(companyId, branchId));
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

        // 3c. Resolve due date — priority: PaymentTerms master > paymentTermsDays integer > net-on-receipt
        //     (D-2, ADR-0040: linked term wins; deprecated integer is fallback; net-on-receipt if neither set)
        LocalDate invoiceDate = totals.finalisedAt() != null
                ? totals.finalisedAt().atZone(ZoneOffset.UTC).toLocalDate()
                : LocalDate.now();

        Customer customer = totals.customerId() != null
                ? customers.findById(totals.customerId()).orElse(null)
                : null;
        // ADR-0041 D1 — resolve the PaymentTerms master once (customer default), then derive the due
        // date and the inherited settlement-discount fields via the shared calculator.
        PaymentTerms terms = (customer != null && customer.getPaymentTermsId() != null)
                ? paymentTermsRepo.findById(customer.getPaymentTermsId()).orElse(null)
                : null;
        Integer netDaysFallback = customer != null ? customer.getPaymentTermsDays() : null;
        LocalDate dueDate = PaymentTermsDueDateCalculator.derive(invoiceDate, terms, netDaysFallback);

        // 3d. Create the open item — NO GL POST (D-5, BR-AR-02)
        // Use outstandingAmount from totals if present; fall back to grossTotalAmount (D-10 v1 default)
        BigDecimal receivable = totals.outstandingAmount() != null
                ? totals.outstandingAmount()
                : totals.grossTotalAmount();

        ArInvoice inv = new ArInvoice(
                companyId, branchId, totals.customerId(),
                ArInvoiceSource.SALE, payload.invoiceUid(), null,
                receivable, totals.currency(),
                invoiceDate, dueDate, null /* SYSTEM — no user actor */);

        // ADR-0036 D-4 FX triple stamp (fix for I-3/I-4 violations):
        // Use the SAME rate the SalesPostingHandler used (stamped on the invoice at finalise).
        // For a base-currency invoice fxRate==1 → base==face → identical behaviour (I-5).
        // Derive baseReceivable from receivable × fxRate so the AR sub-ledger base value
        // matches the DR-AR base posted in the SALES journal (mirror BillMatchServiceImpl).
        // A foreign (currency != base) open item must NEVER persist with fxRate=1 / base NULL.
        BigDecimal invoiceFxRate = totals.fxRate() != null ? totals.fxRate() : BigDecimal.ONE;
        inv.setFxRate(invoiceFxRate);
        inv.setRateAt(totals.rateAt());

        // Resolve base minor units for HALF_UP rounding (TZS=0, most others=2).
        String baseCurrency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");
        int baseScale = baseMinorUnits(baseCurrency);
        BigDecimal baseReceivable = receivable.multiply(invoiceFxRate)
                .setScale(baseScale, RoundingMode.HALF_UP);
        inv.setBaseOriginalAmount(baseReceivable);
        inv.setBaseOutstandingAmount(baseReceivable);

        // ADR-0041 D1 — settlement-discount fields inherited from the customer's payment terms.
        // Data-only (no GL leg). Null when the term carries no early-payment discount.
        if (terms != null) {
            inv.setSettlementDiscountDueDate(
                    PaymentTermsDueDateCalculator.settlementDiscountDueDate(invoiceDate, terms));
            inv.setSettlementDiscountAmount(
                    PaymentTermsDueDateCalculator.settlementDiscountAmount(receivable, terms));
        }

        // Fail loud: a foreign open item must not persist with fxRate=1 / base NULL (I-6 guard).
        if (!totals.currency().equals(baseCurrency)
                && invoiceFxRate.compareTo(BigDecimal.ONE) == 0
                && (totals.fxRate() == null || totals.fxRate().compareTo(BigDecimal.ONE) == 0)) {
            // fxRate defaulted to 1 for a foreign currency — no rate was stamped; this is an anomaly.
            log.warn("ArSalePostedHandler: WARN — foreign AR open item for invoice uid={} currency={} "
                    + "has fxRate=1 / base=face. The invoice may not have been FX-stamped at finalise. "
                    + "Persisting as-is; realize this will cause incorrect realized FX on settlement.",
                    payload.invoiceUid(), totals.currency());
        }

        inv = arInvoices.save(inv);

        audit.record(AuditEvent.of(AuditActions.AR_OPENITEM_CREATE, "ar_invoices",
                        inv.getId(), inv.getUid())
                .detail(Map.of(
                        "sourceInvoiceUid", payload.invoiceUid(),
                        "amount", receivable.toPlainString(),
                        "fxRate", invoiceFxRate.toPlainString(),
                        "baseAmount", baseReceivable.toPlainString(),
                        "actor", "SYSTEM")));

        log.debug("ArSalePostedHandler: open item uid={} created for invoice uid={} amount={}",
                inv.getUid(), payload.invoiceUid(), receivable);
    }

    /** Minor-unit scale for HALF_UP rounding of base amounts (mirrors ArReceiptServiceImpl). */
    private static int baseMinorUnits(String currencyCode) {
        if (currencyCode == null) return 2;
        return switch (currencyCode) {
            case "TZS", "JPY", "KRW" -> 0;
            case "BHD", "KWD", "OMR" -> 3;
            default -> 2;
        };
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
