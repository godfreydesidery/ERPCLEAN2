package com.erp.modules.sales.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.routes.domain.entity.Route;
import com.erp.modules.routes.repository.RouteRepository;
import com.erp.modules.routes.service.RouteService;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.ar.domain.dto.ArBalanceDto;
import com.erp.modules.ar.service.ArBalanceService;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.products.service.PriceResolutionService;
import com.erp.modules.sales.domain.dto.CreateTaxRateRequest;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.InvoicePostingTotalsDto;
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
import com.erp.platform.common.money.ConvertedAmount;
import com.erp.platform.common.money.FxDocumentConverter;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(SalesInvoiceServiceImpl.class);

    private final SalesInvoiceRepository invoices;
    private final SalesInvoiceLineRepository lines;
    private final SalesInvoicePaymentRepository payments;
    private final TaxRateRepository taxRates;
    private final CustomerRepository customers;
    private final AgentRepository agents;
    private final ProductRepository products;
    private final UnitOfMeasureRepository units;
    private final PriceResolutionService priceResolutionService;
    private final ProductBulkPackRepository bulkPacks;
    private final CompanyRepository companies;
    private final SalesInvoiceCodeGenerator codeGen;
    private final InvoiceTotalsCalculator totalsCalc;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;
    private final OutboxPublisher outbox;
    private final RouteService routeService;
    private final RouteRepository routeRepository;
    /** ADR-0014 D-9: credit-limit check at finalise for CREDIT_ACCOUNT customers. */
    private final ArBalanceService arBalanceService;
    private final PermissionResolver permissionResolver;
    /** ADR-0036 D-3/D-4: converts face amounts to base and stamps the FX triple at finalise. */
    private final FxDocumentConverter fxConverter;
    /** ADR-0041 D1: resolves customer payment terms to stamp settlement discount at finalise. */
    private final PaymentTermsRepository paymentTermsRepo;
    /** Cross-module read: resolves the posted SALES journal entry uid for the "View Journal" link. */
    private final JournalEntryRepository journalEntries;

    public SalesInvoiceServiceImpl(SalesInvoiceRepository invoices,
                                   SalesInvoiceLineRepository lines,
                                   SalesInvoicePaymentRepository payments,
                                   TaxRateRepository taxRates,
                                   CustomerRepository customers,
                                   AgentRepository agents,
                                   ProductRepository products,
                                   UnitOfMeasureRepository units,
                                   PriceResolutionService priceResolutionService,
                                   ProductBulkPackRepository bulkPacks,
                                   CompanyRepository companies,
                                   SalesInvoiceCodeGenerator codeGen,
                                   InvoiceTotalsCalculator totalsCalc,
                                   ScopeGuard scopeGuard,
                                   AuditService audit,
                                   OutboxPublisher outbox,
                                   RouteService routeService,
                                   RouteRepository routeRepository,
                                   ArBalanceService arBalanceService,
                                   PermissionResolver permissionResolver,
                                   FxDocumentConverter fxConverter,
                                   PaymentTermsRepository paymentTermsRepo,
                                   JournalEntryRepository journalEntries) {
        this.invoices = invoices;
        this.lines = lines;
        this.payments = payments;
        this.taxRates = taxRates;
        this.customers = customers;
        this.agents = agents;
        this.products = products;
        this.units = units;
        this.priceResolutionService = priceResolutionService;
        this.bulkPacks = bulkPacks;
        this.companies = companies;
        this.codeGen = codeGen;
        this.totalsCalc = totalsCalc;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.outbox = outbox;
        this.routeService = routeService;
        this.routeRepository = routeRepository;
        this.arBalanceService = arBalanceService;
        this.permissionResolver = permissionResolver;
        this.fxConverter = fxConverter;
        this.paymentTermsRepo = paymentTermsRepo;
        this.journalEntries = journalEntries;
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

        // ADR-0041 D1 — early payment-terms override on a DIRECT invoice (optional). When present,
        // resolve scoped to this company and stamp it now; a finalise-time override still wins.
        Long createTermId = resolvePaymentTermsOverrideId(companyId, req.paymentTermsUid());
        if (createTermId != null) {
            inv.setPaymentTermsId(createTermId);
        }

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
        FinaliseInvoiceRequest safeReq = req != null ? req : new FinaliseInvoiceRequest();
        SalesInvoice inv = requireInvoice(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        assertDraft(inv, "finalise");

        List<SalesInvoiceLine> lineList = lines.findByInvoiceIdOrderByLineNo(inv.getId());
        if (lineList.isEmpty()) {
            throw new IllegalStateException("Cannot finalise an invoice with no lines.");
        }

        // Recompute totals one final time and freeze
        totalsCalc.recompute(inv, lineList);

        // ADR-0036 D-4: stamp the immutable FX rate-triple at finalise (totals are now frozen).
        // toBase converts the gross to company base currency; for a base-currency invoice it is the
        // identity short-circuit (rate=1, base==face) — the day-1 no-regression path (D-8). A foreign
        // currency with no effective rate throws FxRateNotFoundException (the document cannot finalise
        // without a rate — OQ-FX-06). The poster (GLPostingSafeInvoker) re-applies the same effective
        // rate on the same posting date, so the stamped base equals the posted-journal base.
        ConvertedAmount fxConv = fxConverter.toBase(
                inv.getGrossTotalAmount(), inv.getCurrency().value(), inv.getCompanyId(), LocalDate.now());
        inv.setFxRate(fxConv.rate());
        inv.setBaseGrossTotalAmount(fxConv.baseAmount());
        inv.setRateAt(fxConv.rateAt());

        // ADR-0014 D-9/D-10: resolve customer kind to decide cash-vs-credit path.
        Customer customer = customers.findById(inv.getCustomerId())
                .orElseThrow(() -> new NotFoundException(
                        "The customer linked to this invoice could not be found."));
        boolean isCreditCustomer = customer.getCustomerKind()
                == com.erp.modules.parties.domain.enums.CustomerKind.CREDIT_ACCOUNT;

        // ADR-0041 D1 — resolve + stamp the payment terms on the SI (data-only). Priority:
        //   1. finalise-time override (safeReq.paymentTermsUid)
        //   2. a term already stamped at create (CreateSalesInvoiceRequest.paymentTermsUid)
        //   3. the customer's default payment_terms_id
        // The downstream AR open item (ArSalePostedHandler) derives the due date + settlement-discount
        // fields via PaymentTermsDueDateCalculator. SO-sourced invoices keep the SO-inherited term
        // (set at delivery-billing) unless a finalise override is explicitly supplied.
        Long resolvedTermId = resolvePaymentTermsOverrideId(inv.getCompanyId(), safeReq.paymentTermsUid());
        if (resolvedTermId == null) {
            resolvedTermId = inv.getPaymentTermsId();   // create-time override or SO-inherited term
        }
        if (resolvedTermId == null
                && customer.getPaymentTermsId() != null
                && paymentTermsRepo.findById(customer.getPaymentTermsId()).isPresent()) {
            resolvedTermId = customer.getPaymentTermsId();
        }
        inv.setPaymentTermsId(resolvedTermId);

        List<SalesInvoicePayment> paymentList = payments.findByInvoiceId(inv.getId());

        if (isCreditCustomer) {
            // Credit customers: validate currency only; no paid-in-full requirement (D-10).
            // Any partial payment is valid; the AR open item carries the residual.
            // Currency check: mirror BR-CUR-07 (same rule as assertPaidInFull, different path).
            for (SalesInvoicePayment p : paymentList) {
                if (!inv.getCurrency().equals(p.getCurrency())) {
                    // BR-CUR-07: payment currency must match the invoice currency
                throw new IllegalStateException(
                            "The payment currency (" + p.getCurrency()
                                    + ") does not match the invoice currency (" + inv.getCurrency()
                                    + "). All payments must be in the same currency as the invoice.");
                }
            }

            // Credit-limit check (ADR-0014 D-9): existing AR balance + this gross must not exceed limit.
            com.erp.platform.common.money.Money creditLimit = customer.getCreditLimit();
            if (creditLimit != null && creditLimit.isPresent()
                    && creditLimit.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                ArBalanceDto balance = arBalanceService.currentBalance(
                        inv.getCompanyId(), inv.getCustomerId());
                BigDecimal projectedBalance = balance.balance().add(inv.getGrossTotalAmount());
                if (projectedBalance.compareTo(creditLimit.getAmount()) > 0) {
                    boolean hasOverride = permissionResolver.hasPermission(
                            RequestContext.get(), "SALES.CREDIT.OVERRIDE", System.currentTimeMillis());
                    if (!hasOverride) {
                        // ADR-0014 D-9 / SALES.CREDIT.OVERRIDE permission required for override
                        throw new IllegalStateException(
                                "This customer's credit limit has been reached. "
                                        + "The outstanding balance would exceed the allowed limit "
                                        + "if this invoice is finalised. "
                                        + "You do not have permission to override the credit limit.");
                    }
                    audit.record(AuditEvent.of(AuditActions.SALES_CREDIT_OVERRIDE, "sales_invoices",
                                    inv.getId(), inv.getUid())
                            .detail(Map.of(
                                    "customerUid", customer.getUid(),
                                    "creditLimit", creditLimit.getAmount().toPlainString(),
                                    "creditLimitCurrency", creditLimit.getCurrency().value(),
                                    "projectedBalance", projectedBalance.toPlainString())));
                }
            }
        } else {
            // Cash customer: full paid-in-full invariant applies (ADR-0008 D-8, BR-SALES-07).
            assertPaidInFull(inv, paymentList);
        }

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

        // ADR-0021 D-6: DIRECT and POS invoices issue stock on finalise (issuesStock=true).
        // POS is a DIRECT-class invoice (ADR-0029 D-5, DocumentOrigin javadoc) — it has no
        // delivery step, so stock must be issued at finalise just like a walk-in invoice.
        // SO-sourced invoices post revenue only — delivery already issued stock (issuesStock=false).
        boolean issuesStock = (inv.getOrigin() == com.erp.modules.sales.domain.enums.DocumentOrigin.DIRECT
                || inv.getOrigin() == com.erp.modules.sales.domain.enums.DocumentOrigin.POS);

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
                        payloadLines,
                        issuesStock,
                        number));

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
        // FLOW-ORDER-TO-CASH-027 (AR path): block void when AR receipts/allocations have been
        // posted against this invoice (settled or partially settled). A void on a settled invoice
        // would leave orphaned AR allocations and an unbalanced sub-ledger.
        if (arBalanceService.hasAllocations(inv.getCompanyId(), inv.getUid())) {
            // FLOW-ORDER-TO-CASH-027: block void when AR receipts/allocations have been posted
            throw new com.erp.platform.common.api.ConflictException(
                    "This invoice cannot be voided because payments have already been received and "
                            + "allocated against it. Please raise a credit note to reverse it.");
        }
        // FLOW-ORDER-TO-CASH-027 (direct-payment path): block void when any direct tender has
        // been applied via sales_invoice_payments. Effective amount = amount − change_amount;
        // a non-zero settled total means cash has changed hands and the invoice must be reversed
        // via a credit note, not silently voided.
        BigDecimal settled = payments.findByInvoiceId(inv.getId()).stream()
                .map(p -> p.getChangeAmount() != null
                        ? p.getAmount().subtract(p.getChangeAmount())
                        : p.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (settled.compareTo(BigDecimal.ZERO) > 0) {
            // FLOW-ORDER-TO-CASH-027: block void when direct tenders have been applied
            throw new com.erp.platform.common.api.ConflictException(
                    "This invoice cannot be voided because direct payments have been applied to it. "
                            + "Please raise a credit note to reverse it.");
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
                new SaleVoidedPayload(inv.getUid(), inv.getCompanyId(), inv.getBranchId(),
                        inv.getInvoiceNumber()));

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
        BigDecimal listPrice = priceResolutionService.resolveUnitListPrice(
                inv.getCompanyId(), product.getId(), unit.getId());

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
        DiscountValidator.validateLineDiscount(req.lineDiscountAmount(), req.lineDiscountPercent());
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
                        .orElseThrow(() -> new NotFoundException("Unit not found.")));
        Product product = resolveProduct(inv.getCompanyId(),
                products.findById(line.getProductId())
                        .map(p -> p.getUid())
                        .orElseThrow(() -> new NotFoundException("Product not found.")));
        BigDecimal qtyInBase = computeQtyInBase(product, unit, req.quantity());

        DiscountValidator.validateLineDiscount(req.lineDiscountAmount(), req.lineDiscountPercent());
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
        if (!inv.getCurrency().value().equals(req.currency())) {
            throw new IllegalArgumentException(
                    "Payment currency " + req.currency()
                            + " does not match invoice currency " + inv.getCurrency().value());
        }

        Long actor = actorId();
        SalesInvoicePayment payment = new SalesInvoicePayment(
                inv, req.tenderType(), req.amount(), req.reference(), actor, actor);
        // ADR-0041 D3 — populate structured instrument links / refs (POS / immediate-payment path).
        // Stamp only the field relevant to the tender type so a CASH tender never carries a card ref.
        applyTenderInstruments(payment, req);
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
                .orElseThrow(() -> new NotFoundException("Payment not found."));
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

    /**
     * ADR-0041 D3 — copy the structured instrument links / refs from the request onto the payment,
     * gated by tender type so each tender carries only its own instrument:
     * <ul>
     *   <li>CASH / MOBILE_MONEY / CHEQUE / CARD → cash_bank_account_id (the account the tender landed in)</li>
     *   <li>CHEQUE → cheque_id</li>
     *   <li>MOBILE_MONEY → mobile_money_ref</li>
     *   <li>CARD → card_ref</li>
     * </ul>
     * All fields are optional — when omitted the payment row keeps the legacy free-text reference only.
     */
    private void applyTenderInstruments(SalesInvoicePayment payment, AddPaymentRequest req) {
        if (req.cashBankAccountId() != null) {
            payment.setCashBankAccountId(req.cashBankAccountId());
        }
        switch (req.tenderType()) {
            case CHEQUE -> {
                if (req.chequeId() != null) {
                    payment.setChequeId(req.chequeId());
                }
            }
            case MOBILE_MONEY -> {
                if (req.mobileMoneyRef() != null && !req.mobileMoneyRef().isBlank()) {
                    payment.setMobileMoneyRef(req.mobileMoneyRef());
                }
            }
            case CARD -> {
                if (req.cardRef() != null && !req.cardRef().isBlank()) {
                    payment.setCardRef(req.cardRef());
                }
            }
            default -> {
                // CASH — no structured instrument beyond the optional cash_bank_account_id above.
            }
        }
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
    public TaxRateDto createTaxRate(CreateTaxRateRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), req.companyId());
        if (taxRates.existsByCompanyIdAndVatStatus(req.companyId(), req.vatStatus())) {
            throw new IllegalStateException(
                    "A tax rate for this VAT classification already exists. Update the existing rate instead.");
        }
        TaxRate rate = new TaxRate(req.companyId(), req.vatStatus(), req.rate(), actorId());
        taxRates.save(rate);
        audit.record(AuditEvent.of(AuditActions.TAXRATE_CREATE, "tax_rates", rate.getId(), rate.getUid())
                .detail(Map.of(
                        "vatStatus", rate.getVatStatus().name(),
                        "rate", req.rate().toPlainString())));
        return TaxRateDto.from(rate);
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
    // GL posting read (ADR-0013 D-12)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<InvoicePostingTotalsDto> findPostingTotalsByUidAndCompany(
            String invoiceUid, Long companyId) {
        return invoices.findByUidAndCompanyId(invoiceUid, companyId)
                .map(inv -> {
                    // ADR-0014 D-10: derive isCashSale from customer kind, not hard-coded true.
                    // CASH_WALK_IN → cash (DR Cash); CREDIT_ACCOUNT → credit (DR AR control).
                    // Fully-paid credit customers also flag as cash (edge case: no receivable).
                    Customer cust = customers.findById(inv.getCustomerId()).orElse(null);
                    boolean isCreditCustomer = cust != null
                            && cust.getCustomerKind() == com.erp.modules.parties.domain.enums.CustomerKind.CREDIT_ACCOUNT;

                    // Compute outstanding: gross − Σ(payments − change)
                    List<SalesInvoicePayment> paymentList = payments.findByInvoiceId(inv.getId());
                    BigDecimal totalTendered = paymentList.stream()
                            .map(p -> {
                                BigDecimal net = p.getAmount();
                                if (p.getChangeAmount() != null) net = net.subtract(p.getChangeAmount());
                                return net;
                            })
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal outstanding = inv.getGrossTotalAmount().subtract(totalTendered)
                            .max(BigDecimal.ZERO);
                    boolean fullyPaid = outstanding.compareTo(BigDecimal.ZERO) == 0;

                    // A credit customer with an outstanding balance is a credit sale (DR AR).
                    // Otherwise (cash customer, or credit customer fully paid) → cash path.
                    boolean isCashSale = !isCreditCustomer || fullyPaid;

                    return new InvoicePostingTotalsDto(
                            inv.getUid(),
                            inv.getStatus().name(),
                            inv.getCurrency().value(),
                            inv.getCustomerId(),
                            isCashSale,
                            inv.getNetTotalAmount(),
                            inv.getVatTotalAmount(),
                            inv.getGrossTotalAmount(),
                            inv.getFinalisedAt(),
                            outstanding,              // ADR-0014 D-10: the AR open-item amount
                            inv.getCostCentreValueId(), // ADR-0025 D-6: dimension defaults
                            inv.getDepartmentValueId(),
                            inv.getProjectId(),        // ADR-0033 D-4c: project tag → revenue leg
                            inv.getProjectTaskId(),
                            // ADR-0036 D-4 FX triple — stamped at finalise (fix: was missing)
                            inv.getFxRate(),
                            inv.getBaseGrossTotalAmount(),
                            inv.getRateAt(),
                            // ADR-0041 D1 — the term stamped at finalise (override or customer default)
                            inv.getPaymentTermsId()
                    );
                });
    }

    // -------------------------------------------------------------------------
    // VAT computation read (ADR-0017 D-6) — additive, no Sales schema change
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public com.erp.modules.sales.domain.dto.VatOutputSummaryDto findVatSummaryForPeriod(
            Long companyId, java.time.LocalDate start, java.time.LocalDate end) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        // Direct JPQL projection — finalised invoices with finalised_at::date in [start, end].
        // Sum vat_total_amount and parse tax_summary JSONB band breakdown per invoice.
        // The tax_summary JSONB has the structure: { "STANDARD": {"taxableBase":..., "vatAmount":...}, ... }
        // We read each invoice's vat_total_amount + tax_summary and aggregate per band in Java.
        List<SalesInvoice> finalisedInPeriod = invoices.findFinalisedInPeriod(
                companyId,
                start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                end.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());

        java.util.Map<String, BigDecimal> bandTaxableBase = new java.util.LinkedHashMap<>();
        java.util.Map<String, BigDecimal> bandOutputVat   = new java.util.LinkedHashMap<>();
        BigDecimal totalOutput = BigDecimal.ZERO;

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        for (SalesInvoice inv : finalisedInPeriod) {
            totalOutput = totalOutput.add(inv.getVatTotalAmount());

            if (inv.getTaxSummary() != null && !inv.getTaxSummary().isBlank()) {
                try {
                    com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(inv.getTaxSummary());
                    root.fields().forEachRemaining(entry -> {
                        String band = entry.getKey();
                        com.fasterxml.jackson.databind.JsonNode node = entry.getValue();
                        BigDecimal base = node.has("taxableBase")
                                ? new BigDecimal(node.get("taxableBase").asText("0"))
                                : BigDecimal.ZERO;
                        BigDecimal vat = node.has("vatAmount")
                                ? new BigDecimal(node.get("vatAmount").asText("0"))
                                : BigDecimal.ZERO;
                        bandTaxableBase.merge(band, base, BigDecimal::add);
                        bandOutputVat.merge(band, vat, BigDecimal::add);
                    });
                } catch (Exception ignored) {
                    // malformed tax_summary — treat as zero-band, vat_total_amount already counted
                }
            }
        }

        java.util.Map<String, com.erp.modules.sales.domain.dto.VatOutputSummaryDto.BandTotalsDto> byBand
                = new java.util.LinkedHashMap<>();
        bandOutputVat.forEach((band, vat) -> byBand.put(band,
                new com.erp.modules.sales.domain.dto.VatOutputSummaryDto.BandTotalsDto(
                        bandTaxableBase.getOrDefault(band, BigDecimal.ZERO), vat)));

        return new com.erp.modules.sales.domain.dto.VatOutputSummaryDto(byBand, totalOutput);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private SalesInvoice requireInvoice(String uid) {
        return Lookups.orNotFound(invoices.findByUid(uid), "SalesInvoice", uid);
    }

    private SalesInvoiceLine requireLine(String lineUid, Long invoiceId) {
        return lines.findByUidAndInvoiceId(lineUid, invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice line not found."));
    }

    private TaxRate requireTaxRate(String uid) {
        return Lookups.orNotFound(taxRates.findByUid(uid), "TaxRate", uid);
    }

    private Long resolveCompanyId(String companyUid) {
        return companies.findByUid(companyUid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
    }

    /** Cross-tenant safe customer lookup (F15 pattern). */
    private Customer resolveCustomer(Long companyId, String customerUid) {
        return customers.findByCompanyIdAndUid(companyId, customerUid)
                .orElseThrow(() -> new NotFoundException("Customer not found."));
    }

    /**
     * ADR-0041 D1 — resolves an OPTIONAL payment-terms override uid to its id, scoped to the
     * invoice's company. Returns null when {@code paymentTermsUid} is blank (caller then falls back
     * to the create-time / customer-default term). Throws when a non-blank uid does not resolve in
     * this company (mirrors SalesOrderServiceImpl.resolvePaymentTermsId — fail loud on a bad uid).
     */
    private Long resolvePaymentTermsOverrideId(Long companyId, String paymentTermsUid) {
        if (paymentTermsUid == null || paymentTermsUid.isBlank()) {
            return null;
        }
        return paymentTermsRepo.findByUid(paymentTermsUid)
                .filter(pt -> companyId.equals(pt.getCompanyId()))
                .map(pt -> pt.getId())
                .orElseThrow(() -> new NotFoundException("Payment terms not found."));
    }

    /**
     * Resolve agent id: explicit uid override, or auto-default to logged-in user's internal agent.
     * If neither is possible, throw (BR-SALES-06 — every invoice must have an agent).
     */
    private Long resolveAgentId(Long companyId, String agentUid, RequestContext.Principal ctx) {
        if (agentUid != null && !agentUid.isBlank()) {
            Agent agent = agents.findByCompanyIdAndUid(companyId, agentUid)
                    .orElseThrow(() -> new NotFoundException("Agent not found."));
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
        // Technical context goes to the log only; the user-facing message stays friendly and
        // carries no internal codes/identifiers (error-message hygiene standing rule).
        log.warn("Agent resolution failed (BR-SALES-06): no agentUid supplied and no internal agent "
                + "for companyId={} userId={}", companyId, ctx != null ? ctx.userId() : null);
        throw new IllegalArgumentException(
                "This sales order has no agent assigned. Assign an agent before invoicing.");
    }

    private void assertAgentActive(Agent agent) {
        if (agent.getStatus() == MasterStatus.ARCHIVED) {
            throw new IllegalArgumentException(
                    "Agent is archived and cannot be selected.");
        }
    }

    /** Cross-tenant safe product lookup (F15 pattern). */
    private Product resolveProduct(Long companyId, String productUid) {
        return products.findByCompanyIdAndUid(companyId, productUid)
                .orElseThrow(() -> new NotFoundException("Product not found."));
    }

    private void assertSellable(Product product) {
        if (!product.isSellable()) {
            throw new IllegalArgumentException(
                    "Product is not sellable.");
        }
        if (product.getStatus() == MasterStatus.ARCHIVED) {
            throw new IllegalArgumentException(
                    "Product is archived and cannot be added to an invoice.");
        }
    }

    /** Cross-tenant safe unit lookup (F15 pattern). */
    private UnitOfMeasure resolveUnit(Long companyId, String unitUid) {
        return units.findByCompanyIdAndUid(companyId, unitUid)
                .orElseThrow(() -> new NotFoundException("Unit of measure not found."));
    }

    /**
     * Snapshot the VAT rate from the company's tax_rates table (ADR-0008 D-5b, FR-SALES-10).
     * Throws if the rate is not configured (should not happen post-seeder).
     */
    private BigDecimal resolveVatRate(Long companyId, Product product) {
        return taxRates.findByCompanyIdAndVatStatus(companyId, product.getVatStatus())
                .map(TaxRate::getRate)
                .orElseThrow(() -> new IllegalStateException(
                        "VAT rate not configured for status " + product.getVatStatus() + "."));
    }

    /**
     * Compute qty_in_base: base unit → 1:1; configured bulk-pack → multiply by factorToBase;
     * any other unit → rejected (data-integrity guard, prevents silent mis-conversion).
     */
    private BigDecimal computeQtyInBase(Product product, UnitOfMeasure unit, BigDecimal qty) {
        // Base unit: 1:1 conversion.
        if (unit.getId().equals(product.getBaseUnit().getId())) {
            return qty;
        }
        // Bulk-pack unit: find the configured factor.
        Optional<ProductBulkPack> pack = bulkPacks.findByProductId(product.getId()).stream()
                .filter(bp -> bp.getUnit().getId().equals(unit.getId()))
                .findFirst();
        if (pack.isPresent()) {
            return qty.multiply(pack.get().getFactorToBase());
        }
        // Unit is neither the base nor a configured pack — reject it.
        throw new IllegalStateException(
                unit.getName() + " is not a valid unit for " + product.getName()
                        + ". Use the product's base unit or a configured pack unit.");
    }

    private void assertDraft(SalesInvoice inv, String operation) {
        // BR-SALES-08: only DRAFT invoices are mutable
        if (inv.getStatus() != InvoiceStatus.DRAFT) {
            throw new IllegalStateException(
                    "Cannot " + operation + " an invoice that is no longer in draft. "
                            + "Only draft invoices can be changed.");
        }
    }

    /**
     * Paid-in-full invariant (ADR-0008 D-8, FR-SALES-18, BR-SALES-07).
     * Σ(amount − coalesce(change, 0)) must equal gross_total_amount.
     * Cash over-tender → set change_amount on the cash row (change accepted).
     * Mobile-money over-tender → reject (no change for mobile).
     */
    private void assertPaidInFull(SalesInvoice inv, List<SalesInvoicePayment> paymentList) {
        // FR-SALES-18: at least one tender must be recorded before finalising a cash invoice
        if (paymentList.isEmpty()) {
            throw new IllegalStateException(
                    "This invoice has no payments recorded. Please add at least one payment before finalising.");
        }

        BigDecimal gross = inv.getGrossTotalAmount();
        BigDecimal totalTendered = paymentList.stream()
                .map(SalesInvoicePayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int cmp = totalTendered.compareTo(gross);
        // BR-SALES-07: tenders must cover the invoice gross total
        if (cmp < 0) {
            throw new IllegalStateException(
                    "The total payments received do not cover the invoice amount. "
                            + "Required: " + gross + ", received: " + totalTendered + ".");
        }
        if (cmp > 0) {
            // Over-tender: only CASH accepts change (BR-SALES-07)
            BigDecimal change = totalTendered.subtract(gross);
            SalesInvoicePayment cashPayment = paymentList.stream()
                    .filter(p -> p.getTenderType() == TenderType.CASH)
                    .findFirst()
                    // BR-SALES-07: only CASH accepts change
                    .orElseThrow(() -> new IllegalStateException(
                            "The amount tendered exceeds the invoice total, but there is no cash "
                                    + "payment to give change against. Please adjust the tender amounts."));
            // Mobile money over-tender check: if no cash row or mobile covers the excess
            boolean onlyCashOverTender = paymentList.stream()
                    .filter(p -> p.getTenderType() == TenderType.MOBILE_MONEY)
                    .map(SalesInvoicePayment::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .compareTo(gross) <= 0;
            if (!onlyCashOverTender) {
                // BR-SALES-07: mobile money does not support change
                throw new IllegalStateException(
                        "Mobile money payments cannot be over-tendered — change is not accepted for this payment method. "
                                + "Please adjust the mobile money amount to match the invoice total.");
            }
            cashPayment.setChangeAmount(change);
        }
        // Validate all payment currencies match header (BR-CUR-07)
        for (SalesInvoicePayment p : paymentList) {
            if (!inv.getCurrency().equals(p.getCurrency())) {
                // BR-CUR-07: payment currency must match the invoice currency
                throw new IllegalStateException(
                        "The payment currency (" + p.getCurrency()
                                + ") does not match the invoice currency (" + inv.getCurrency()
                                + "). All payments must be in the same currency as the invoice.");
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
        String postedGlEntryUid = resolvePostedGlEntryUid(inv);
        return SalesInvoiceDto.from(inv, customerName, agentName, routeUid, routeCode, routeName,
                postedGlEntryUid);
    }

    /**
     * Read-time resolution of the posted SALES journal entry for the "View Journal" link
     * (mirrors AP SupplierBill's postedGlEntryUid). Looks up by the SAME (sourceType, sourceRef)
     * pair {@code GLPostingSafeInvoker.postSaleInNewTx} posted under: {@code JournalSourceType.SALES}
     * + the invoice uid (see {@code SaleVoidingHandler}, which finds the same entry to reverse it).
     * Defensive: never throws — null when unposted (DRAFT/VOID) or not found.
     */
    private String resolvePostedGlEntryUid(SalesInvoice inv) {
        if (inv.getStatus() != InvoiceStatus.FINALISED) {
            return null;
        }
        try {
            return journalEntries
                    .findByCompanyIdAndSourceTypeAndSourceRef(
                            inv.getCompanyId(), JournalSourceType.SALES, inv.getUid())
                    .map(JournalEntry::getUid)
                    .orElse(null);
        } catch (Exception ex) {
            log.warn("SalesInvoiceServiceImpl: failed to resolve posted GL entry for invoice uid={} — {}",
                    inv.getUid(), ex.getMessage());
            return null;
        }
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
