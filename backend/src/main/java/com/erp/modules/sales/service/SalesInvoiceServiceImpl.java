package com.erp.modules.sales.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.routes.domain.entity.Route;
import com.erp.modules.routes.repository.RouteRepository;
import com.erp.modules.routes.service.RouteService;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.OverrideLinePriceRequest;
import com.erp.modules.sales.domain.dto.SaleFinalisedPayload;
import com.erp.modules.sales.domain.dto.SaleVoidedPayload;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceLineDto;
import com.erp.modules.sales.domain.dto.SalesInvoicePaymentDto;
import com.erp.modules.sales.domain.dto.TaxRateDto;
import com.erp.modules.sales.domain.dto.UpdateInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.UpdateTaxRateRequest;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.entity.SalesInvoiceLine;
import com.erp.modules.sales.domain.entity.SalesInvoicePayment;
import com.erp.modules.sales.domain.entity.TaxRate;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.repository.SalesInvoiceLineRepository;
import com.erp.modules.sales.repository.SalesInvoicePaymentRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.modules.sales.repository.TaxRateRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales invoice lifecycle service (ADR-0008, FR-SALES-01..25).
 *
 * <p>Security: {@code scopeGuard.assertCanActIn} on EVERY read path (brief §4a).
 * Cross-tenant refs (customer, agent, product, unit) resolved scoped to invoice's company (F15).
 * Paid-in-full invariant enforced at finalise (D-8). Finalised invoices immutable (BR-SALES-08).
 */
@Service
@Transactional
public class SalesInvoiceServiceImpl implements SalesInvoiceService {

    private final SalesInvoiceRepository invoices;
    private final SalesInvoiceLineRepository lines;
    private final SalesInvoicePaymentRepository payments;
    private final TaxRateRepository taxRates;
    private final CustomerRepository customers;
    private final AgentRepository agents;
    private final ProductRepository products;
    private final UnitOfMeasureRepository units;
    private final ProductPriceRepository prices;
    private final ProductBulkPackRepository bulkPacks;
    private final CompanyRepository companies;
    private final SalesInvoiceCodeGenerator codeGen;
    private final InvoiceTotalsCalculator totalsCalc;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final RouteService routeService;
    private final RouteRepository routeRepository;

    public SalesInvoiceServiceImpl(SalesInvoiceRepository invoices,
                                   SalesInvoiceLineRepository lines,
                                   SalesInvoicePaymentRepository payments,
                                   TaxRateRepository taxRates,
                                   CustomerRepository customers,
                                   AgentRepository agents,
                                   ProductRepository products,
                                   UnitOfMeasureRepository units,
                                   ProductPriceRepository prices,
                                   ProductBulkPackRepository bulkPacks,
                                   CompanyRepository companies,
                                   SalesInvoiceCodeGenerator codeGen,
                                   InvoiceTotalsCalculator totalsCalc,
                                   ScopeGuard scopeGuard,
                                   AuditService audit,
                                   OutboxPublisher outbox,
                                   RouteService routeService,
                                   RouteRepository routeRepository) {
        this.invoices = invoices;
        this.lines = lines;
        this.payments = payments;
        this.taxRates = taxRates;
        this.customers = customers;
        this.agents = agents;
        this.products = products;
        this.units = units;
        this.prices = prices;
        this.bulkPacks = bulkPacks;
        this.companies = companies;
        this.codeGen = codeGen;
        this.totalsCalc = totalsCalc;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.outbox = outbox;
        this.routeService = routeService;
        this.routeRepository = routeRepository;
    }

    // -------------------------------------------------------------------------
    // Invoice lifecycle
    // -------------------------------------------------------------------------

    @Override
    public SalesInvoiceDto create(CreateSalesInvoiceRequest req) {
        // Resolve companyUid → id + assert scope (brief §4b)
        Long companyId = resolveCompanyId(req.companyUid());
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, companyId);

        // Branch from RequestContext — NOT the body (brief §4b)
        Long branchId = ctx != null ? ctx.branchId() : null;
        if (branchId == null) {
            throw new IllegalStateException("No active branch in context.");
        }

        // Resolve customer scoped to this company (F15)
        Customer customer = resolveCustomer(companyId, req.customerUid());

        // Resolve agent: explicit uid or auto-default to logged-in user's internal agent (FR-SALES-15)
        Long agentId = resolveAgentId(companyId, req.agentUid(), ctx);

        // Resolve route: explicit routeUid takes precedence; else default from agent's primary route
        // (FR-ROUTE-13, ADR-0012 D-6b). Fail open to null — never blocks a sale (BR-ROUTE-05).
        Long routeId = resolveRouteId(companyId, agentId, branchId, req.routeUid());

        SalesInvoice inv = new SalesInvoice(companyId, branchId,
                customer.getId(), agentId, req.currency(), actorId());
        inv.setNotes(req.notes());
        inv.setRouteId(routeId);

        SalesInvoice saved = invoices.save(inv);
        audit.record(AuditEvent.of(AuditActions.SALES_INVOICE_CREATE, "sales_invoices",
                        saved.getId(), saved.getUid())
                .detail(Map.of(
                        "customerUid", req.customerUid(),
                        "agentId", String.valueOf(agentId),
                        "documentType", saved.getDocumentType().name())));

        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public SalesInvoiceDto getByUid(String uid) {
        SalesInvoice inv = requireInvoice(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        return toDto(inv);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SalesInvoiceDto> list(Long companyId, String q, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (q != null && !q.isBlank()) {
            return invoices.search(companyId, q, pageable).map(this::toDto);
        }
        return invoices.findByCompanyId(companyId, pageable).map(this::toDto);
    }

    @Override
    public void finalise(String uid, FinaliseInvoiceRequest req) {
        SalesInvoice inv = requireInvoice(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        assertDraft(inv, "finalise");

        List<SalesInvoiceLine> lineList = lines.findByInvoiceIdOrderByLineNo(inv.getId());
        if (lineList.isEmpty()) {
            throw new IllegalStateException("Cannot finalise an invoice with no lines.");
        }

        // Recompute totals one final time and freeze
        totalsCalc.recompute(inv, lineList);

        // Paid-in-full invariant (ADR-0008 D-8)
        List<SalesInvoicePayment> paymentList = payments.findByInvoiceId(inv.getId());
        assertPaidInFull(inv, paymentList);

        // Assign invoice number (ADR-0008 D-7, FR-SALES-23)
        String number = codeGen.next(inv.getCompanyId());
        inv.setInvoiceNumber(number);
        inv.setStatus(InvoiceStatus.FINALISED);
        inv.setFinalisedAt(Instant.now());
        inv.setFinalisedBy(actorId());
        inv.setUpdatedAt(Instant.now());
        inv.setUpdatedBy(actorId());

        // Emit SALE.FINALISED outbox event (ADR-0008 D-9, ADR-0009 D-3) — inside the finalise TX.
        // Build line items: look up product uid for each line (scalar, batch by id).
        Map<Long, String> productUids = products.findAllById(
                        lineList.stream().map(SalesInvoiceLine::getProductId).toList())
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.erp.modules.products.domain.entity.Product::getId,
                        com.erp.modules.products.domain.entity.Product::getUid));

        List<SaleFinalisedPayload.LineItem> payloadLines = lineList.stream()
                .map(l -> new SaleFinalisedPayload.LineItem(
                        l.getProductId(),
                        productUids.getOrDefault(l.getProductId(), ""),
                        l.getUnitId(),
                        l.getQtyInBase()))
                .toList();

        outbox.publish(
                DomainEventType.SALE_FINALISED,
                DomainEventType.AGG_SALES_INVOICE,
                inv.getId(),
                inv.getUid(),
                inv.getCompanyId(),
                inv.getBranchId(),
                new SaleFinalisedPayload(
                        inv.getUid(),
                        inv.getCompanyId(),
                        inv.getBranchId(),
                        inv.getFinalisedAt(),
                        payloadLines));

        audit.record(AuditEvent.of(AuditActions.SALES_INVOICE_FINALISE, "sales_invoices",
                        inv.getId(), inv.getUid())
                .detail(Map.of(
                        "invoiceNumber", number,
                        "grossTotal", inv.getGrossTotalAmount().toPlainString(),
                        "vatTotal", inv.getVatTotalAmount().toPlainString())));
    }

    @Override
    public void voidInvoice(String uid, VoidInvoiceRequest req) {
        SalesInvoice inv = requireInvoice(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        if (inv.getStatus() != InvoiceStatus.FINALISED) {
            throw new IllegalStateException(
                    "Only FINALISED invoices can be voided; current status: " + inv.getStatus());
        }
        inv.setStatus(InvoiceStatus.VOID);
        inv.setVoidedAt(Instant.now());
        inv.setVoidedBy(actorId());
        inv.setVoidReason(req.reason());
        inv.setUpdatedAt(Instant.now());
        inv.setUpdatedBy(actorId());

        // Emit SALE.VOIDED in the same TX (ADR-0009 D-3) — Stock reverses the issued movements
        // for this invoice from its own ledger (OQ-STOCK-10). No-op if the invoice never issued
        // stock (i.e. nothing was emitted at finalise time — Stock dedupes/anomaly-logs).
        outbox.publish(
                DomainEventType.SALE_VOIDED,
                DomainEventType.AGG_SALES_INVOICE,
                inv.getId(),
                inv.getUid(),
                inv.getCompanyId(),
                inv.getBranchId(),
                new SaleVoidedPayload(inv.getUid(), inv.getCompanyId(), inv.getBranchId()));

        audit.record(AuditEvent.of(AuditActions.SALES_INVOICE_VOID, "sales_invoices",
                        inv.getId(), inv.getUid())
                .detail(Map.of(
                        "invoiceNumber", inv.getInvoiceNumber(),
                        "voidReason", req.reason())));
    }

    // -------------------------------------------------------------------------
    // Lines
    // -------------------------------------------------------------------------

    @Override
    public SalesInvoiceLineDto addLine(String invoiceUid, AddInvoiceLineRequest req) {
        SalesInvoice inv = requireInvoice(invoiceUid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        assertDraft(inv, "add a line to");

        // Resolve product scoped to invoice company (F15)
        Product product = resolveProduct(inv.getCompanyId(), req.productUid());
        assertSellable(product);

        // Resolve unit scoped to invoice company (F15)
        UnitOfMeasure unit = resolveUnit(inv.getCompanyId(), req.unitUid());

        // Snapshot price from price list (BR-SALES-03)
        BigDecimal listPrice = resolveListPrice(product, inv.getCompanyId(), inv.getCurrency());

        // Snapshot VAT rate from tax_rates (ADR-0008 D-5b)
        BigDecimal vatRate = resolveVatRate(inv.getCompanyId(), product);

        // Compute qty_in_base: qty × factor_to_base if bulk pack unit, else qty (base unit)
        BigDecimal qtyInBase = computeQtyInBase(product, unit, req.quantity());

        // Next line number
        short nextLineNo = (short) (lines.findMaxLineNo(inv.getId()) + 1);

        SalesInvoiceLine line = new SalesInvoiceLine(
                inv, nextLineNo,
                product.getId(), product.getCode(), product.getName(),
                unit.getId(), unit.getName(),
                req.quantity(), qtyInBase,
                listPrice, listPrice,   // applied = list initially
                product.getVatStatus(), vatRate,
                actorId());
        line.setLineDiscountAmount(req.lineDiscountAmount());
        line.setLineDiscountPercent(req.lineDiscountPercent());

        SalesInvoiceLine saved = lines.save(line);

        // Recompute header totals
        recomputeTotals(inv);

        audit.record(AuditEvent.of(AuditActions.SALES_INVOICE_LINE_ADD, "sales_invoice_lines",
                        inv.getId(), inv.getUid())
                .detail(Map.of(
                        "productUid", req.productUid(),
                        "quantity", req.quantity().toPlainString(),
                        "unitPrice", listPrice.toPlainString())));

        return SalesInvoiceLineDto.from(saved);
    }

    @Override
    public SalesInvoiceLineDto updateLine(String invoiceUid, String lineUid,
                                          UpdateInvoiceLineRequest req) {
        SalesInvoice inv = requireInvoice(invoiceUid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        assertDraft(inv, "update a line on");

        // Resolve line scoped to parent invoice (F16)
        SalesInvoiceLine line = requireLine(lineUid, inv.getId());

        // Recompute qty_in_base for new quantity
        UnitOfMeasure unit = resolveUnit(inv.getCompanyId(),
                units.findById(line.getUnitId())
                        .map(u -> u.getUid())
                        .orElseThrow(() -> new NotFoundException("Unit not found: " + line.getUnitId())));
        Product product = resolveProduct(inv.getCompanyId(),
                products.findById(line.getProductId())
                        .map(p -> p.getUid())
                        .orElseThrow(() -> new NotFoundException("Product not found: " + line.getProductId())));
        BigDecimal qtyInBase = computeQtyInBase(product, unit, req.quantity());

        line.setQuantity(req.quantity());
        line.setQtyInBase(qtyInBase);
        line.setLineDiscountAmount(req.lineDiscountAmount());
        line.setLineDiscountPercent(req.lineDiscountPercent());
        line.setUpdatedAt(Instant.now());
        line.setUpdatedBy(actorId());

        recomputeTotals(inv);

        audit.record(AuditEvent.of(AuditActions.SALES_INVOICE_LINE_UPDATE, "sales_invoice_lines",
                        inv.getId(), inv.getUid())
                .detail(Map.of("lineUid", lineUid, "quantity", req.quantity().toPlainString())));

        return SalesInvoiceLineDto.from(line);
    }

    @Override
    public void removeLine(String invoiceUid, String lineUid) {
        SalesInvoice inv = requireInvoice(invoiceUid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        assertDraft(inv, "remove a line from");

        SalesInvoiceLine line = requireLine(lineUid, inv.getId());
        lines.delete(line);

        recomputeTotals(inv);

        audit.record(AuditEvent.of(AuditActions.SALES_INVOICE_LINE_REMOVE, "sales_invoice_lines",
                        inv.getId(), inv.getUid())
                .detail(Map.of("lineUid", lineUid)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesInvoiceLineDto> listLines(String invoiceUid) {
        SalesInvoice inv = requireInvoice(invoiceUid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        return lines.findByInvoiceIdOrderByLineNo(inv.getId()).stream()
                .map(SalesInvoiceLineDto::from)
                .toList();
    }

    @Override
    public SalesInvoiceLineDto overrideLinePrice(String invoiceUid, String lineUid,
                                                  OverrideLinePriceRequest req) {
        SalesInvoice inv = requireInvoice(invoiceUid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        assertDraft(inv, "override a price on");

        SalesInvoiceLine line = requireLine(lineUid, inv.getId());
        BigDecimal before = line.getListPriceAmount();
        BigDecimal after  = req.unitPriceAmount();

        line.setUnitPriceAmount(after);
        line.setPriceOverridden(true);
        line.setOverriddenBy(actorId());
        line.setUpdatedAt(Instant.now());
        line.setUpdatedBy(actorId());

        recomputeTotals(inv);

        audit.record(AuditEvent.of(AuditActions.SALES_INVOICE_LINE_OVERRIDE, "sales_invoice_lines",
                        inv.getId(), inv.getUid())
                .detail(Map.of(
                        "lineUid", lineUid,
                        "listPrice", before.toPlainString(),
                        "appliedPrice", after.toPlainString())));

        return SalesInvoiceLineDto.from(line);
    }

    // -------------------------------------------------------------------------
    // Payments
    // -------------------------------------------------------------------------

    @Override
    public SalesInvoicePaymentDto addPayment(String invoiceUid, AddPaymentRequest req) {
        SalesInvoice inv = requireInvoice(invoiceUid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        assertDraft(inv, "add a payment to");

        // Currency must match header (BR-CUR-07)
        if (!inv.getCurrency().equals(req.currency())) {
            throw new IllegalArgumentException(
                    "Payment currency " + req.currency()
                            + " does not match invoice currency " + inv.getCurrency());
        }

        Long actor = actorId();
        SalesInvoicePayment payment = new SalesInvoicePayment(
                inv, req.tenderType(), req.amount(), req.reference(), actor, actor);
        SalesInvoicePayment saved = payments.save(payment);

        audit.record(AuditEvent.of(AuditActions.SALES_INVOICE_PAYMENT_ADD, "sales_invoice_payments",
                        inv.getId(), inv.getUid())
                .detail(Map.of(
                        "tenderType", req.tenderType().name(),
                        "amount", req.amount().toPlainString())));

        return SalesInvoicePaymentDto.from(saved);
    }

    @Override
    public void removePayment(String invoiceUid, String paymentUid) {
        SalesInvoice inv = requireInvoice(invoiceUid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        assertDraft(inv, "remove a payment from");

        SalesInvoicePayment payment = payments.findByUidAndInvoiceId(paymentUid, inv.getId())
                .orElseThrow(() -> new NotFoundException("Payment not found: " + paymentUid));
        payments.delete(payment);

        audit.record(AuditEvent.of(AuditActions.SALES_INVOICE_PAYMENT_REMOVE, "sales_invoice_payments",
                        inv.getId(), inv.getUid())
                .detail(Map.of(
                        "tenderType", payment.getTenderType().name(),
                        "amount", payment.getAmount().toPlainString())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesInvoicePaymentDto> listPayments(String invoiceUid) {
        SalesInvoice inv = requireInvoice(invoiceUid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        return payments.findByInvoiceId(inv.getId()).stream()
                .map(SalesInvoicePaymentDto::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Tax rates
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public TaxRateDto getTaxRateByUid(String uid) {
        TaxRate rate = requireTaxRate(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), rate.getCompanyId());
        return TaxRateDto.from(rate);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TaxRateDto> listTaxRates(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return taxRates.findAll().stream()
                .filter(r -> r.getCompanyId().equals(companyId))
                .map(TaxRateDto::from)
                .toList();
    }

    @Override
    public TaxRateDto updateTaxRate(String uid, UpdateTaxRateRequest req) {
        TaxRate rate = requireTaxRate(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), rate.getCompanyId());
        BigDecimal before = rate.getRate();
        rate.setRate(req.rate());
        rate.setUpdatedAt(Instant.now());
        rate.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.TAXRATE_UPDATE, "tax_rates",
                        rate.getId(), rate.getUid())
                .detail(Map.of(
                        "vatStatus", rate.getVatStatus().name(),
                        "before", before.toPlainString(),
                        "after", req.rate().toPlainString())));

        return TaxRateDto.from(rate);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private SalesInvoice requireInvoice(String uid) {
        return Lookups.orNotFound(invoices.findByUid(uid), "SalesInvoice", uid);
    }

    private SalesInvoiceLine requireLine(String lineUid, Long invoiceId) {
        return lines.findByUidAndInvoiceId(lineUid, invoiceId)
                .orElseThrow(() -> new NotFoundException("SalesInvoiceLine not found: " + lineUid));
    }

    private TaxRate requireTaxRate(String uid) {
        return Lookups.orNotFound(taxRates.findByUid(uid), "TaxRate", uid);
    }

    private Long resolveCompanyId(String companyUid) {
        return companies.findByUid(companyUid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyUid));
    }

    /** Cross-tenant safe customer lookup (F15 pattern). */
    private Customer resolveCustomer(Long companyId, String customerUid) {
        return customers.findByCompanyIdAndUid(companyId, customerUid)
                .orElseThrow(() -> new NotFoundException("Customer not found: " + customerUid));
    }

    /**
     * Resolve agent id: explicit uid override, or auto-default to logged-in user's internal agent.
     * If neither is possible, throw (BR-SALES-06 — every invoice must have an agent).
     */
    private Long resolveAgentId(Long companyId, String agentUid, RequestContext.Principal ctx) {
        if (agentUid != null && !agentUid.isBlank()) {
            Agent agent = agents.findByCompanyIdAndUid(companyId, agentUid)
                    .orElseThrow(() -> new NotFoundException("Agent not found: " + agentUid));
            assertAgentActive(agent);
            return agent.getId();
        }
        // Auto-default: logged-in user's internal agent (FR-SALES-15)
        if (ctx != null && ctx.userId() != null) {
            Optional<Long> auto = agents.findInternalAgentIdByCompanyAndUser(companyId, ctx.userId());
            if (auto.isPresent()) {
                return auto.get();
            }
        }
        throw new IllegalArgumentException(
                "No agent specified and the logged-in user has no internal agent record in this company. "
                        + "Please supply agentUid (BR-SALES-06).");
    }

    private void assertAgentActive(Agent agent) {
        if (agent.getStatus() == MasterStatus.ARCHIVED) {
            throw new IllegalArgumentException(
                    "Agent is archived and not selectable (BR-PARTY-10): " + agent.getUid());
        }
    }

    /** Cross-tenant safe product lookup (F15 pattern). */
    private Product resolveProduct(Long companyId, String productUid) {
        return products.findByCompanyIdAndUid(companyId, productUid)
                .orElseThrow(() -> new NotFoundException("Product not found: " + productUid));
    }

    private void assertSellable(Product product) {
        if (!product.isSellable()) {
            throw new IllegalArgumentException(
                    "Product is not sellable (BR-SALES-02): " + product.getUid());
        }
        if (product.getStatus() == MasterStatus.ARCHIVED) {
            throw new IllegalArgumentException(
                    "Product is archived (BR-SALES-02): " + product.getUid());
        }
    }

    /** Cross-tenant safe unit lookup (F15 pattern). */
    private UnitOfMeasure resolveUnit(Long companyId, String unitUid) {
        return units.findByCompanyIdAndUid(companyId, unitUid)
                .orElseThrow(() -> new NotFoundException("UnitOfMeasure not found: " + unitUid));
    }

    /**
     * Resolve the applicable list price for the product in the given company.
     * Uses the company's default price list (FR-SALES-07). Rejects un-priced products (BR-SALES-03).
     * In v1 we use the first active price found for the product in this company.
     */
    private BigDecimal resolveListPrice(Product product, Long companyId, String currency) {
        List<ProductPrice> priceRows = prices.findByProductId(product.getId());
        if (priceRows.isEmpty()) {
            throw new IllegalArgumentException(
                    "Product has no price on any price list (BR-SALES-03): " + product.getUid());
        }
        // Use first price found (company-scoped by denormalised companyId on ProductPrice)
        ProductPrice pp = priceRows.stream()
                .filter(p -> companyId.equals(p.getCompanyId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product has no price for this company (BR-SALES-03): " + product.getUid()));
        if (pp.getPrice() == null || pp.getPrice().getAmount() == null) {
            throw new IllegalArgumentException(
                    "Product price is not set (BR-SALES-03): " + product.getUid());
        }
        return pp.getPrice().getAmount();
    }

    /**
     * Snapshot the VAT rate from the company's tax_rates table (ADR-0008 D-5b, FR-SALES-10).
     * Throws if the rate is not configured (should not happen post-seeder).
     */
    private BigDecimal resolveVatRate(Long companyId, Product product) {
        return taxRates.findByCompanyIdAndVatStatus(companyId, product.getVatStatus())
                .map(TaxRate::getRate)
                .orElseThrow(() -> new IllegalStateException(
                        "VAT rate not configured for " + product.getVatStatus()
                                + " in company " + companyId));
    }

    /**
     * Compute qty_in_base: if the chosen unit is a bulk pack of this product, multiply by
     * factor_to_base; otherwise qty is already in base units.
     */
    private BigDecimal computeQtyInBase(Product product, UnitOfMeasure unit, BigDecimal qty) {
        // Check if unit matches any bulk pack for this product (filter in memory — list is small)
        Optional<ProductBulkPack> pack = bulkPacks.findByProductId(product.getId()).stream()
                .filter(bp -> bp.getUnit().getId().equals(unit.getId()))
                .findFirst();
        if (pack.isPresent()) {
            return qty.multiply(pack.get().getFactorToBase());
        }
        // Base unit: qty_in_base == qty
        return qty;
    }

    private void assertDraft(SalesInvoice inv, String operation) {
        if (inv.getStatus() != InvoiceStatus.DRAFT) {
            throw new IllegalStateException(
                    "Cannot " + operation + " an invoice in status " + inv.getStatus()
                            + " (BR-SALES-08). Only DRAFT invoices are mutable.");
        }
    }

    /**
     * Paid-in-full invariant (ADR-0008 D-8, FR-SALES-18, BR-SALES-07).
     * Σ(amount − coalesce(change, 0)) must equal gross_total_amount.
     * Cash over-tender → set change_amount on the cash row (change accepted).
     * Mobile-money over-tender → reject (no change for mobile).
     */
    private void assertPaidInFull(SalesInvoice inv, List<SalesInvoicePayment> paymentList) {
        if (paymentList.isEmpty()) {
            throw new IllegalStateException(
                    "Invoice has no payments. Add tenders before finalising (FR-SALES-18).");
        }

        BigDecimal gross = inv.getGrossTotalAmount();
        BigDecimal totalTendered = paymentList.stream()
                .map(SalesInvoicePayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int cmp = totalTendered.compareTo(gross);
        if (cmp < 0) {
            throw new IllegalStateException(
                    "Tenders under-cover the gross total. Required: " + gross
                            + ", tendered: " + totalTendered + " (BR-SALES-07).");
        }
        if (cmp > 0) {
            // Over-tender: only CASH accepts change (BR-SALES-07)
            BigDecimal change = totalTendered.subtract(gross);
            SalesInvoicePayment cashPayment = paymentList.stream()
                    .filter(p -> p.getTenderType() == TenderType.CASH)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Over-tender of " + change
                                    + " with no CASH tender to receive change (BR-SALES-07)."));
            // Mobile money over-tender check: if no cash row or mobile covers the excess
            boolean onlyCashOverTender = paymentList.stream()
                    .filter(p -> p.getTenderType() == TenderType.MOBILE_MONEY)
                    .map(SalesInvoicePayment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .compareTo(gross) <= 0;
            if (!onlyCashOverTender) {
                throw new IllegalStateException(
                        "Mobile-money over-tender is not accepted (BR-SALES-07). "
                                + "Change can only be given for cash.");
            }
            cashPayment.setChangeAmount(change);
        }
        // Validate all payment currencies match header (BR-CUR-07)
        for (SalesInvoicePayment p : paymentList) {
            if (!inv.getCurrency().equals(p.getCurrency())) {
                throw new IllegalStateException(
                        "Payment currency " + p.getCurrency()
                                + " does not match invoice currency " + inv.getCurrency()
                                + " (BR-CUR-07).");
            }
        }
    }

    private void recomputeTotals(SalesInvoice inv) {
        List<SalesInvoiceLine> lineList = lines.findByInvoiceIdOrderByLineNo(inv.getId());
        totalsCalc.recompute(inv, lineList);
        inv.setUpdatedAt(Instant.now());
        inv.setUpdatedBy(actorId());
    }

    /** Build response DTO with enriched customer, agent, and route fields. */
    private SalesInvoiceDto toDto(SalesInvoice inv) {
        String customerName = customers.findById(inv.getCustomerId())
                .map(c -> c.getDisplayName())
                .orElse(null);
        String agentName = agents.findById(inv.getAgentId())
                .map(a -> a.getDisplayName())
                .orElse(null);
        // Route enrichment: nullable — resolve live uid/code/name from the scalar route_id (ADR-0012 D-6d)
        String routeUid = null;
        String routeCode = null;
        String routeName = null;
        if (inv.getRouteId() != null) {
            Optional<Route> routeOpt = routeRepository.findById(inv.getRouteId());
            if (routeOpt.isPresent()) {
                Route r = routeOpt.get();
                routeUid  = r.getUid();
                routeCode = r.getCode();
                routeName = r.getName();
            }
        }
        return SalesInvoiceDto.from(inv, customerName, agentName, routeUid, routeCode, routeName);
    }

    /**
     * Resolve route id for invoice create (ADR-0012 D-6b).
     * Priority: explicit routeUid in request → default from agent's primary route.
     * Fails open to null on any error so the sale is never blocked (BR-ROUTE-05).
     */
    private Long resolveRouteId(Long companyId, Long agentId, Long branchId, String routeUid) {
        try {
            if (routeUid != null && !routeUid.isBlank()) {
                // Explicit operator selection: resolve scoped to company (same-company guard)
                return routeRepository.findByCompanyIdAndUid(companyId, routeUid)
                        .map(Route::getId)
                        .orElse(null);
            }
            // Auto-default from agent's primary route, branch-filtered and ACTIVE (FR-ROUTE-13)
            return routeService.findPrimaryRouteIdForAgentAtBranch(companyId, agentId, branchId)
                    .orElse(null);
        } catch (Exception ex) {
            // Fail open — invoice creation must never be blocked by route resolution (BR-ROUTE-05)
            return null;
        }
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
