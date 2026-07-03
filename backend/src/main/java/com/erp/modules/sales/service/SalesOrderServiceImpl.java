package com.erp.modules.sales.service;

import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.dto.SubmitForApprovalRequest;
import com.erp.modules.approvals.domain.enums.ApprovalRequestStatus;
import com.erp.modules.approvals.service.ApprovalEngine;
import com.erp.modules.ar.domain.dto.ArBalanceDto;
import com.erp.modules.ar.service.ArBalanceService;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.entity.CustomerAddress;
import com.erp.modules.parties.domain.enums.CreditStatus;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerAddressRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.sales.domain.dto.AddSalesOrderLineRequest;
import com.erp.modules.sales.domain.dto.CancelSalesOrderRequest;
import com.erp.modules.sales.domain.dto.CreateSalesOrderRequest;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.domain.dto.SalesOrderLineDto;
import com.erp.modules.sales.domain.entity.Quotation;
import com.erp.modules.sales.domain.entity.QuotationLine;
import com.erp.modules.sales.domain.entity.SalesOrder;
import com.erp.modules.sales.domain.entity.SalesOrderLine;
import com.erp.modules.sales.domain.enums.SalesOrderStatus;
import com.erp.modules.sales.repository.QuotationLineRepository;
import com.erp.modules.sales.repository.QuotationRepository;
import com.erp.modules.sales.repository.SalesOrderLineRepository;
import com.erp.modules.sales.repository.SalesOrderRepository;
import com.erp.modules.sales.repository.TaxRateRepository;
import com.erp.modules.stock.service.StockReservationService;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.money.Money;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SalesOrderServiceImpl implements SalesOrderService {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderServiceImpl.class);

    /** Document type key registered with the approvals engine for sales orders. */
    private static final String APPROVAL_DOC_TYPE = "SALES_ORDER";

    private final SalesOrderRepository     orders;
    private final SalesOrderLineRepository orderLines;
    private final QuotationRepository      quotations;
    private final QuotationLineRepository  quotationLines;
    private final CustomerRepository       customers;
    private final CustomerAddressRepository customerAddresses;
    private final PaymentTermsRepository   paymentTermsRepo;
    private final AgentRepository          agents;
    private final CompanyRepository        companies;
    private final BranchRepository         branches;
    private final ProductRepository        products;
    private final UnitOfMeasureRepository  units;
    private final ProductPriceRepository   prices;
    private final ProductBulkPackRepository bulkPacks;
    private final TaxRateRepository        taxRates;
    private final StockReservationService  reservationService;
    private final OrderToCashNumberGenerator numberGen;
    private final SalesOrderTotalsCalculator totalsCalc;
    private final ScopeGuard               scopeGuard;
    private final AuditService             audit;
    /** ADR-0040 D-5: credit-limit + status check at SO confirm (mirrors SalesInvoiceServiceImpl). */
    private final ArBalanceService         arBalanceService;
    private final PermissionResolver       permissionResolver;
    private final ApprovalEngine           approvalEngine;
    /** D-4: automatic amount-threshold approval gate (extends the PR #189 engine-derived flow). */
    private final SalesApprovalGate        salesApprovalGate;

    public SalesOrderServiceImpl(SalesOrderRepository orders,
                                 SalesOrderLineRepository orderLines,
                                 QuotationRepository quotations,
                                 QuotationLineRepository quotationLines,
                                 CustomerRepository customers,
                                 CustomerAddressRepository customerAddresses,
                                 PaymentTermsRepository paymentTermsRepo,
                                 AgentRepository agents,
                                 CompanyRepository companies,
                                 BranchRepository branches,
                                 ProductRepository products,
                                 UnitOfMeasureRepository units,
                                 ProductPriceRepository prices,
                                 ProductBulkPackRepository bulkPacks,
                                 TaxRateRepository taxRates,
                                 StockReservationService reservationService,
                                 OrderToCashNumberGenerator numberGen,
                                 SalesOrderTotalsCalculator totalsCalc,
                                 ScopeGuard scopeGuard,
                                 AuditService audit,
                                 ArBalanceService arBalanceService,
                                 PermissionResolver permissionResolver,
                                 ApprovalEngine approvalEngine,
                                 SalesApprovalGate salesApprovalGate) {
        this.orders           = orders;
        this.orderLines       = orderLines;
        this.quotations       = quotations;
        this.quotationLines   = quotationLines;
        this.customers        = customers;
        this.customerAddresses = customerAddresses;
        this.paymentTermsRepo = paymentTermsRepo;
        this.agents           = agents;
        this.companies        = companies;
        this.branches         = branches;
        this.products         = products;
        this.units            = units;
        this.prices           = prices;
        this.bulkPacks        = bulkPacks;
        this.taxRates         = taxRates;
        this.reservationService = reservationService;
        this.numberGen        = numberGen;
        this.totalsCalc       = totalsCalc;
        this.scopeGuard       = scopeGuard;
        this.audit            = audit;
        this.arBalanceService = arBalanceService;
        this.permissionResolver = permissionResolver;
        this.approvalEngine   = approvalEngine;
        this.salesApprovalGate = salesApprovalGate;
    }

    @Override
    public SalesOrderDto create(CreateSalesOrderRequest req) {
        Long companyId = resolveCompanyId(req.companyUid());
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, companyId);
        Long branchId = requireBranchId(ctx);

        Customer customer = resolveCustomer(companyId, req.customerUid());
        Long agentId = resolveAgentId(companyId, req.agentUid(), ctx);

        DiscountValidator.validateDocDiscount(req.docDiscountAmount(), req.docDiscountPercent());

        SalesOrder order = new SalesOrder(companyId, branchId, customer.getId(), agentId,
                req.currency(), req.orderDate(), actorId());
        order.setDocDiscountAmount(req.docDiscountAmount());
        order.setDocDiscountPercent(req.docDiscountPercent());
        order.setNotes(req.notes());
        // ADR-0031 D-7 back-link: set when called from CRM OpportunityConversionService
        order.setSourceOpportunityUid(req.sourceOpportunityUid());
        order.setOrderNumber(numberGen.nextSalesOrder(companyId));

        // ADR-0041 D1 — resolve payment terms: request uid > customer default. Stored at create.
        Long paymentTermsId = resolvePaymentTermsId(req.paymentTermsUid(), customer);
        order.setPaymentTermsId(paymentTermsId);

        // ADR-0041 D2 — snapshot ship-to / bill-to addresses when an explicit uid is supplied.
        // No auto-default when omitted (FORK: leave null).
        applyAddressSnapshot(order, customer.getId(), req.shipToAddressUid(), req.billToAddressUid());

        SalesOrder saved = orders.save(order);
        audit.record(AuditEvent.of(AuditActions.SO_CREATE, "sales_orders",
                saved.getId(), saved.getUid())
                .detail(Map.of("orderNumber", saved.getOrderNumber())));
        return toDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public SalesOrderDto getByUid(String uid) {
        SalesOrder o = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), o.getCompanyId());
        return toDto(o);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SalesOrderDto> list(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return orders.findByCompanyId(companyId, pageable).map(this::toDtoNoLines);
    }

    @Override
    public SalesOrderLineDto addLine(String orderUid, AddSalesOrderLineRequest req) {
        SalesOrder order = require(orderUid);
        scopeGuard.assertCanActIn(RequestContext.get(), order.getCompanyId());
        assertDraft(order);
        assertNotInApproval(order);

        Product product = resolveProduct(order.getCompanyId(), req.productUid());
        assertSellable(product);
        UnitOfMeasure unit = resolveUnit(order.getCompanyId(), req.unitUid());
        BigDecimal listPrice = resolveListPrice(product, order.getCompanyId());
        // Issue #6: unitPriceOverride must not be negative (revenue leakage / silent-zero bug).
        // @PositiveOrZero on the DTO is the first-line guard; this is defence-in-depth for
        // programmatic callers that bypass bean validation.
        if (req.unitPriceOverride() != null
                && req.unitPriceOverride().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "unitPriceOverride must be >= 0; got: " + req.unitPriceOverride().toPlainString());
        }
        BigDecimal appliedPrice = req.unitPriceOverride() != null ? req.unitPriceOverride() : listPrice;
        BigDecimal vatRate = resolveVatRate(order.getCompanyId(), product);
        BigDecimal qtyInBase = computeQtyInBase(product, unit, req.quantity());
        short lineNo = (short) (orderLines.findMaxLineNo(order.getId()) + 1);

        DiscountValidator.validateLineDiscount(req.lineDiscountAmount(), req.lineDiscountPercent());

        SalesOrderLine line = new SalesOrderLine(
                order.getId(), order.getCompanyId(), order.getBranchId(), lineNo,
                product.getId(), product.getCode(), product.getName(),
                unit.getId(), unit.getName(),
                req.quantity(), qtyInBase,
                listPrice, appliedPrice,
                product.getVatStatus(), vatRate,
                order.getCurrency().value(), actorId());
        line.setLineDiscountAmount(req.lineDiscountAmount());
        line.setLineDiscountPercent(req.lineDiscountPercent());

        SalesOrderLine saved = orderLines.save(line);
        recomputeTotals(order);

        audit.record(AuditEvent.of(AuditActions.SO_LINE_ADD, "sales_order_lines",
                order.getId(), order.getUid())
                .detail(Map.of("productUid", req.productUid())));
        return SalesOrderLineDto.from(saved);
    }

    @Override
    public void removeLine(String orderUid, String lineUid) {
        SalesOrder order = require(orderUid);
        scopeGuard.assertCanActIn(RequestContext.get(), order.getCompanyId());
        assertDraft(order);
        assertNotInApproval(order);
        SalesOrderLine line = orderLines.findByUidAndSalesOrderId(lineUid, order.getId())
                .orElseThrow(() -> new NotFoundException("Order line not found."));
        orderLines.delete(line);
        recomputeTotals(order);
        audit.record(AuditEvent.of(AuditActions.SO_LINE_REMOVE, "sales_order_lines",
                order.getId(), order.getUid())
                .detail(Map.of("lineUid", lineUid)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SalesOrderLineDto> listLines(String orderUid) {
        SalesOrder order = require(orderUid);
        scopeGuard.assertCanActIn(RequestContext.get(), order.getCompanyId());
        return orderLines.findBySalesOrderIdOrderByLineNo(order.getId())
                .stream().map(SalesOrderLineDto::from).toList();
    }

    @Override
    public SalesOrderDto confirm(String orderUid) {
        try {
            return doConfirm(orderUid);
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.warn("SalesOrderService.confirm: optimistic lock conflict for uid={} — retrying once", orderUid);
            return doConfirm(orderUid);
        }
    }

    private SalesOrderDto doConfirm(String orderUid) {
        SalesOrder order = require(orderUid);
        scopeGuard.assertCanActIn(RequestContext.get(), order.getCompanyId());
        autoSubmitForApprovalIfOverThreshold(order);
        assertApprovalClearance(order);
        assertDraft(order);

        List<SalesOrderLine> lines = orderLines.findBySalesOrderIdOrderByLineNo(order.getId());
        if (lines.isEmpty()) {
            throw new IllegalStateException("Cannot confirm a sales order with no lines.");
        }

        // ADR-0040 D-5: credit-control hard-block — checked BEFORE stock reservation.
        // Only applies to CREDIT_ACCOUNT customers; cash/walk-in customers are unaffected.
        assertCreditClearance(order);

        // Reserve stock for each line (D-4/D-5)
        for (SalesOrderLine line : lines) {
            line.setQtyReservedBase(line.getQtyOrderedBase());
            line.setUpdatedAt(Instant.now());
            line.setUpdatedBy(actorId());
            reservationService.applyReservationDelta(
                    order.getCompanyId(), order.getBranchId(),
                    line.getProductId(), line.getQtyOrderedBase(), actorId());
        }

        // ADR-0041 D1 — backfill payment terms from the customer default at confirm if not already
        // resolved at create (e.g. created before the customer had a default term set).
        if (order.getPaymentTermsId() == null) {
            Customer customer = customers.findById(order.getCustomerId()).orElse(null);
            if (customer != null && customer.getPaymentTermsId() != null) {
                order.setPaymentTermsId(customer.getPaymentTermsId());
            }
        }

        order.setStatus(SalesOrderStatus.CONFIRMED);
        order.setConfirmedAt(Instant.now());
        order.setUpdatedAt(Instant.now());
        order.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.SO_CONFIRM, "sales_orders",
                order.getId(), order.getUid())
                .detail(Map.of("orderNumber", order.getOrderNumber())));
        return toDto(order);
    }

    /**
     * Submits a DRAFT order to the approvals engine (ADR-0022 D-7 seam). Nothing is persisted on
     * the SalesOrder entity — the engine (queried by {@code documentType}/{@code documentUid}) is
     * the source of truth; {@link #buildDto} resolves the current status back onto the response.
     */
    @Override
    public SalesOrderDto submitForApproval(String orderUid) {
        SalesOrder order = require(orderUid);
        scopeGuard.assertCanActIn(RequestContext.get(), order.getCompanyId());

        if (order.getStatus() != SalesOrderStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only a draft order can be submitted for approval; this order is already "
                            + order.getStatus() + ".");
        }

        Optional<ApprovalRequestDto> existing = approvalEngine.getApprovalState(
                APPROVAL_DOC_TYPE, order.getUid(), order.getCompanyId());
        if (existing.isPresent()) {
            // The approvals engine enforces one lifecycle per document uid (BR-APR-07/08): a
            // terminal request cannot be reopened, and re-submitting one would surface a raw 409.
            // Handle each existing state with clear guidance instead.
            ApprovalRequestStatus st = existing.get().status();
            if (st == ApprovalRequestStatus.PENDING) {
                // Already awaiting a decision — idempotent no-op, don't double-submit.
                return toDto(order);
            }
            if (st == ApprovalRequestStatus.APPROVED) {
                throw new IllegalStateException("This order has already been approved.");
            }
            // REJECTED / CANCELLED / RECALLED — closed and cannot be reopened for the same order.
            throw new IllegalStateException(
                    "This order's previous approval is closed and cannot be reopened. "
                            + "Cancel this order and raise a new one to submit for approval again.");
        }

        // Resolve branch uid + customer name via COMPANY-SCOPED finders (not bare findById) — the
        // ids come from the already-scope-checked order, and the scoped finders keep
        // TenantScopingRulesTest green without touching its frozen store.
        String branchUid = branches.findByIdAndCompany_Id(order.getBranchId(), order.getCompanyId())
                .map(Branch::getUid).orElse(null);
        String customerName = customers.findByCompanyIdAndId(order.getCompanyId(), order.getCustomerId())
                .map(Customer::getDisplayName).orElse(null);
        BigDecimal amount = order.getGrossTotalAmount() != null
                ? order.getGrossTotalAmount() : BigDecimal.ZERO;
        Long actorId = actorId();

        String summary = customerName != null
                ? "SO " + order.getOrderNumber() + " — " + customerName
                : "SO " + order.getOrderNumber();
        SubmitForApprovalRequest req = new SubmitForApprovalRequest(
                APPROVAL_DOC_TYPE,
                order.getUid(),
                amount,
                order.getCurrency().value(),
                order.getCompanyId(),
                branchUid,
                actorId != null ? actorId : 0L,
                summary);

        approvalEngine.submitForApproval(req);

        audit.record(AuditEvent.of(AuditActions.SO_SUBMIT_FOR_APPROVAL, "sales_orders",
                order.getId(), order.getUid())
                .detail(Map.of("orderNumber", order.getOrderNumber())));

        return toDto(order);
    }

    @Override
    public void cancel(String orderUid, CancelSalesOrderRequest req) {
        try {
            doCancel(orderUid, req);
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.warn("SalesOrderService.cancel: optimistic lock conflict for uid={} — retrying once", orderUid);
            doCancel(orderUid, req);
        }
    }

    private void doCancel(String orderUid, CancelSalesOrderRequest req) {
        SalesOrder order = require(orderUid);
        scopeGuard.assertCanActIn(RequestContext.get(), order.getCompanyId());
        // FLOW-ORDER-TO-CASH-028: FULFILLED, PARTIALLY_INVOICED, INVOICED, and CLOSED orders
        // have committed stock movements or posted invoices and must not be cancelled.
        // CANCELLED is already terminal. Allow cancellation only from DRAFT or CONFIRMED
        // (where reservations can still be safely released), or PARTIALLY_FULFILLED
        // (open qty can be released; fulfilled lines already have delivery records).
        switch (order.getStatus()) {
            case CANCELLED ->
                throw new IllegalStateException("This order is already CANCELLED.");
            case FULFILLED ->
                throw new com.erp.platform.common.api.ConflictException(
                        "Cannot cancel a FULFILLED order. All stock has been dispatched. "
                                + "Raise a sales return to reverse it.");
            case PARTIALLY_INVOICED ->
                throw new com.erp.platform.common.api.ConflictException(
                        "Cannot cancel a PARTIALLY_INVOICED order. "
                                + "One or more invoices have already been raised.");
            case INVOICED ->
                throw new com.erp.platform.common.api.ConflictException(
                        "Cannot cancel an INVOICED order. All lines have been invoiced.");
            case CLOSED ->
                throw new com.erp.platform.common.api.ConflictException(
                        "Cannot cancel a CLOSED order.");
            default -> { /* DRAFT, CONFIRMED, PARTIALLY_FULFILLED — proceed */ }
        }

        // Release remaining reservation for each line (D-4/D-5)
        List<SalesOrderLine> lines = orderLines.findBySalesOrderIdOrderByLineNo(order.getId());
        for (SalesOrderLine line : lines) {
            if (line.getQtyReservedBase().compareTo(BigDecimal.ZERO) > 0) {
                reservationService.applyReservationDelta(
                        order.getCompanyId(), order.getBranchId(),
                        line.getProductId(), line.getQtyReservedBase().negate(), actorId());
                line.setQtyReservedBase(BigDecimal.ZERO);
                line.setUpdatedAt(Instant.now());
                line.setUpdatedBy(actorId());
            }
        }

        order.setStatus(SalesOrderStatus.CANCELLED);
        order.setCancelledAt(Instant.now());
        order.setCancelReason(req != null ? req.reason() : null);
        order.setUpdatedAt(Instant.now());
        order.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.SO_CANCEL, "sales_orders",
                order.getId(), order.getUid())
                .detail(Map.of("reason", req != null && req.reason() != null ? req.reason() : "")));
    }

    @Override
    public SalesOrderDto setAgent(String uid, String agentUid) {
        SalesOrder order = require(uid);
        // Scope-check from the LOADED entity (rule: never from a caller param).
        scopeGuard.assertCanActIn(RequestContext.get(), order.getCompanyId());

        // Guard status: allow setting/changing the agent only while the order can still produce an
        // uninvoiced invoice — i.e. before any invoice exists. Allowed: DRAFT, CONFIRMED,
        // PARTIALLY_FULFILLED, FULFILLED. Rejected once invoiced, closed, or cancelled.
        switch (order.getStatus()) {
            case DRAFT, CONFIRMED, PARTIALLY_FULFILLED, FULFILLED -> { /* proceed */ }
            case CANCELLED ->
                throw new ConflictException(
                        "This order has been cancelled, so its agent cannot be changed.");
            default ->
                throw new ConflictException(
                        "This order has already been invoiced, so its agent can no longer be changed.");
        }

        // Resolve the agent exactly as create() does: must exist, be active, belong to the company.
        Long agentId = resolveAgentId(order.getCompanyId(), agentUid, RequestContext.get());

        order.setAgentId(agentId);
        order.setUpdatedAt(Instant.now());
        order.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.SO_SET_AGENT, "sales_orders",
                order.getId(), order.getUid())
                .detail(Map.of("agentUid", agentUid)));
        return toDto(order);
    }

    @Override
    public SalesOrderDto createFromQuotation(String quotationUid) {
        Quotation quote = quotations.findByUid(quotationUid)
                .orElseThrow(() -> new NotFoundException("Quotation not found."));

        // Principal/scope already asserted by QuotationService.accept
        SalesOrder order = new SalesOrder(
                quote.getCompanyId(), quote.getBranchId(),
                quote.getCustomerId(), quote.getAgentId(),
                quote.getCurrency().value(), LocalDate.now(), actorId());
        order.setSourceQuotationUid(quote.getUid());
        order.setDocDiscountAmount(quote.getDocDiscountAmount());
        order.setDocDiscountPercent(quote.getDocDiscountPercent());
        order.setNotes(quote.getNotes());
        order.setOrderNumber(numberGen.nextSalesOrder(quote.getCompanyId()));

        SalesOrder saved = orders.save(order);

        // Copy each quotation line verbatim (OQ-SO-07: keep quoted pricing)
        List<QuotationLine> qLines = quotationLines.findByQuotationIdOrderByLineNo(quote.getId());
        for (QuotationLine ql : qLines) {
            SalesOrderLine ol = new SalesOrderLine(
                    saved.getId(), saved.getCompanyId(), saved.getBranchId(), ql.getLineNo(),
                    ql.getProductId(), ql.getProductCode(), ql.getProductName(),
                    ql.getUnitId(), ql.getUnitName(),
                    ql.getQuantity(), ql.getQtyInBase(),
                    ql.getListPriceAmount(), ql.getUnitPriceAmount(),
                    ql.getVatStatus(), ql.getVatRate(),
                    ql.getCurrency().value(), actorId());
            ol.setLineDiscountAmount(ql.getLineDiscountAmount());
            ol.setLineDiscountPercent(ql.getLineDiscountPercent());
            ol.setNetAmount(ql.getNetAmount());
            ol.setVatAmount(ql.getVatAmount());
            ol.setGrossAmount(ql.getGrossAmount());
            orderLines.save(ol);
        }

        // Copy totals from quote (same pricing → same totals)
        saved.setNetTotalAmount(quote.getNetTotalAmount());
        saved.setVatTotalAmount(quote.getVatTotalAmount());
        saved.setGrossTotalAmount(quote.getGrossTotalAmount());

        audit.record(AuditEvent.of(AuditActions.SO_CREATE, "sales_orders",
                saved.getId(), saved.getUid())
                .detail(Map.of("orderNumber", saved.getOrderNumber(),
                        "sourceQuotationUid", quotationUid)));
        return toDto(saved);
    }

    @Override
    public void recomputeStatus(Long salesOrderId) {
        SalesOrder order = orders.findById(salesOrderId)
                .orElseThrow(() -> new NotFoundException("Sales order not found."));
        if (order.getStatus() == SalesOrderStatus.CANCELLED) return;

        List<SalesOrderLine> lines = orderLines.findBySalesOrderIdOrderByLineNo(salesOrderId);
        BigDecimal totalOrdered   = lines.stream().map(SalesOrderLine::getQtyOrderedBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFulfilled = lines.stream().map(SalesOrderLine::getQtyFulfilledBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalInvoiced  = lines.stream().map(SalesOrderLine::getQtyInvoicedBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        SalesOrderStatus newStatus = deriveStatus(totalOrdered, totalFulfilled, totalInvoiced);
        order.setStatus(newStatus);
        order.setUpdatedAt(Instant.now());
        order.setUpdatedBy(actorId());
    }

    /**
     * D-2 rollup total function: deterministic, never ambiguous.
     */
    static SalesOrderStatus deriveStatus(BigDecimal totalOrdered,
                                         BigDecimal totalFulfilled,
                                         BigDecimal totalInvoiced) {
        boolean fullyFulfilled = totalFulfilled.compareTo(totalOrdered) >= 0;
        boolean fullyInvoiced  = totalInvoiced.compareTo(totalFulfilled) >= 0
                && totalFulfilled.compareTo(BigDecimal.ZERO) > 0;
        boolean anyFulfilled   = totalFulfilled.compareTo(BigDecimal.ZERO) > 0;
        boolean anyInvoiced    = totalInvoiced.compareTo(BigDecimal.ZERO) > 0;

        if (fullyFulfilled && fullyInvoiced)  return SalesOrderStatus.CLOSED;
        if (fullyFulfilled && anyInvoiced)    return SalesOrderStatus.PARTIALLY_INVOICED;
        if (fullyFulfilled)                   return SalesOrderStatus.FULFILLED;
        if (anyFulfilled)                     return SalesOrderStatus.PARTIALLY_FULFILLED;
        return SalesOrderStatus.CONFIRMED;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private SalesOrder require(String uid) {
        return Lookups.orNotFound(orders.findByUid(uid), "SalesOrder", uid);
    }

    private void assertDraft(SalesOrder order) {
        if (order.getStatus() != SalesOrderStatus.DRAFT) {
            throw new IllegalStateException(
                    "Cannot modify a non-DRAFT sales order; current: " + order.getStatus());
        }
    }

    /**
     * Content-freeze gate (ADR-0022 D-7 seam): while an order is in the approval flow (PENDING) or
     * already APPROVED, its lines must not change — otherwise the order that gets confirmed could
     * differ from the one the approver saw, defeating amount-threshold approval policies. Since the
     * order stays DRAFT for the whole approval cycle (status is engine-derived, not persisted), the
     * plain {@link #assertDraft} check is not enough on the content mutators. Terminal-closed states
     * (REJECTED/CANCELLED/RECALLED) and never-submitted orders stay editable.
     */
    private void assertNotInApproval(SalesOrder order) {
        approvalEngine.getApprovalState(APPROVAL_DOC_TYPE, order.getUid(), order.getCompanyId())
                .map(ApprovalRequestDto::status)
                .filter(s -> s == ApprovalRequestStatus.PENDING || s == ApprovalRequestStatus.APPROVED)
                .ifPresent(s -> {
                    throw new IllegalStateException(
                            "This order is in the approval process and its lines cannot be changed. "
                                    + "Cancel it and raise a new order to make changes.");
                });
    }

    /**
     * Approvals-engine gate at confirm (ADR-0022 D-7 seam): a PENDING request blocks confirm
     * outright, a REJECTED one blocks it permanently. APPROVED, CANCELLED, RECALLED, or never
     * submitted (empty) all proceed unchanged — backward compatible with orders that never went
     * through {@link #submitForApproval(String)}.
     */
    private void assertApprovalClearance(SalesOrder order) {
        Optional<ApprovalRequestDto> state = approvalEngine.getApprovalState(
                APPROVAL_DOC_TYPE, order.getUid(), order.getCompanyId());
        if (state.isEmpty()) {
            return;
        }
        ApprovalRequestStatus status = state.get().status();
        if (status == ApprovalRequestStatus.PENDING) {
            throw new IllegalStateException(
                    "This order is awaiting approval and cannot be confirmed yet.");
        }
        if (status == ApprovalRequestStatus.REJECTED) {
            throw new IllegalStateException(
                    "This order's approval was rejected and it cannot be confirmed.");
        }
    }

    /**
     * D-4: automatic amount-threshold approval gate, run BEFORE {@link #assertApprovalClearance}.
     * Only acts when there is NO existing engine request for this order — if one already exists
     * (submitted manually via {@link #submitForApproval(String)}, or by a previous confirm attempt),
     * this is a no-op and {@link #assertApprovalClearance} governs the outcome, so we never
     * double-submit.
     *
     * <p>When {@link SalesApprovalGate#requiresApproval} says the order is over-threshold, submits
     * it to the engine. If the engine had no matching policy it auto-approves the request
     * (BR-APR-09) and confirm proceeds; otherwise the request is PENDING and confirm is blocked
     * with a friendly message directing the operator to wait for the decision.
     */
    private void autoSubmitForApprovalIfOverThreshold(SalesOrder order) {
        Optional<ApprovalRequestDto> existing = approvalEngine.getApprovalState(
                APPROVAL_DOC_TYPE, order.getUid(), order.getCompanyId());
        if (existing.isPresent()) {
            return;
        }

        String branchUid = branches.findByIdAndCompany_Id(order.getBranchId(), order.getCompanyId())
                .map(Branch::getUid).orElse(null);
        if (!salesApprovalGate.requiresApproval(order, branchUid)) {
            return;
        }

        ApprovalRequestDto result = salesApprovalGate.submit(order, branchUid);
        audit.record(AuditEvent.of(AuditActions.SO_SUBMIT_FOR_APPROVAL, "sales_orders",
                order.getId(), order.getUid())
                .detail(Map.of("orderNumber", order.getOrderNumber(), "trigger", "auto_threshold")));

        if (result.status() != ApprovalRequestStatus.APPROVED) {
            throw new IllegalStateException(
                    "This order exceeds the approval threshold and has been submitted for "
                            + "approval. It can be confirmed once it is approved.");
        }
    }

    private Long resolveCompanyId(String uid) {
        return companies.findByUid(uid).map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
    }

    private Customer resolveCustomer(Long companyId, String uid) {
        return customers.findByCompanyIdAndUid(companyId, uid)
                .orElseThrow(() -> new NotFoundException("Customer not found."));
    }

    /**
     * ADR-0041 D1 — resolves the PaymentTerms id for an order: the request uid takes priority,
     * else the customer's default {@code payment_terms_id}. Returns null when neither resolves.
     */
    private Long resolvePaymentTermsId(String paymentTermsUid, Customer customer) {
        if (paymentTermsUid != null && !paymentTermsUid.isBlank()) {
            return paymentTermsRepo.findByUid(paymentTermsUid)
                    .map(pt -> pt.getId())
                    .orElseThrow(() -> new NotFoundException("Payment terms not found."));
        }
        return customer != null ? customer.getPaymentTermsId() : null;
    }

    /**
     * ADR-0041 D2 — resolves a ship-to / bill-to customer-address uid, validates it belongs to the
     * order's customer, and snapshots the formatted text (immutable). No auto-default when omitted.
     */
    private void applyAddressSnapshot(SalesOrder order, Long customerId,
                                      String shipToAddressUid, String billToAddressUid) {
        if (shipToAddressUid != null && !shipToAddressUid.isBlank()) {
            CustomerAddress addr = resolveCustomerAddress(customerId, shipToAddressUid);
            order.setShipToAddressId(addr.getId());
            order.setShipToAddressText(CustomerAddressSnapshot.format(addr));
        }
        if (billToAddressUid != null && !billToAddressUid.isBlank()) {
            CustomerAddress addr = resolveCustomerAddress(customerId, billToAddressUid);
            order.setBillToAddressId(addr.getId());
            order.setBillToAddressText(CustomerAddressSnapshot.format(addr));
        }
    }

    /** Loads a customer address by uid and asserts it belongs to the document's customer (D2). */
    private CustomerAddress resolveCustomerAddress(Long customerId, String addressUid) {
        CustomerAddress addr = customerAddresses.findByUid(addressUid)
                .orElseThrow(() -> new NotFoundException("Customer address not found."));
        if (!addr.getCustomerId().equals(customerId)) {
            throw new ConflictException(
                    "Customer address does not belong to this customer.");
        }
        return addr;
    }

    private Long resolveAgentId(Long companyId, String agentUid, RequestContext.Principal ctx) {
        if (agentUid != null && !agentUid.isBlank()) {
            Agent agent = agents.findByCompanyIdAndUid(companyId, agentUid)
                    .orElseThrow(() -> new NotFoundException("Agent not found."));
            if (agent.getStatus() == MasterStatus.ARCHIVED) {
                throw new IllegalArgumentException("Agent is archived and cannot be selected.");
            }
            return agent.getId();
        }
        if (ctx != null && ctx.userId() != null) {
            Optional<Long> auto = agents.findInternalAgentIdByCompanyAndUser(companyId, ctx.userId());
            if (auto.isPresent()) return auto.get();
        }
        return null;
    }

    private Product resolveProduct(Long companyId, String uid) {
        return products.findByCompanyIdAndUid(companyId, uid)
                .orElseThrow(() -> new NotFoundException("Product not found."));
    }

    private void assertSellable(Product product) {
        if (!product.isSellable() || product.getStatus() == MasterStatus.ARCHIVED) {
            throw new IllegalArgumentException("Product is not sellable.");
        }
    }

    private UnitOfMeasure resolveUnit(Long companyId, String uid) {
        return units.findByCompanyIdAndUid(companyId, uid)
                .orElseThrow(() -> new NotFoundException("Unit of measure not found."));
    }

    private BigDecimal resolveListPrice(Product product, Long companyId) {
        return prices.findByProductId(product.getId()).stream()
                .filter(p -> companyId.equals(p.getCompanyId()))
                .findFirst()
                .map(ProductPrice::getPrice)
                .filter(m -> m != null && m.getAmount() != null)
                .map(m -> m.getAmount())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product has no price configured for this company."));
    }

    private BigDecimal resolveVatRate(Long companyId, Product product) {
        return taxRates.findByCompanyIdAndVatStatus(companyId, product.getVatStatus())
                .map(r -> r.getRate())
                .orElseThrow(() -> new IllegalStateException(
                        "VAT rate not configured for " + product.getVatStatus()));
    }

    private BigDecimal computeQtyInBase(Product product, UnitOfMeasure unit, BigDecimal qty) {
        // Base unit: 1:1 conversion.
        if (unit.getId().equals(product.getBaseUnit().getId())) {
            return qty;
        }
        // Bulk-pack unit: find the configured factor.
        Optional<com.erp.modules.products.domain.entity.ProductBulkPack> pack =
                bulkPacks.findByProductId(product.getId()).stream()
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

    /**
     * ADR-0040 D-5 credit-control gate at SO confirm.
     *
     * <p>Three independent block conditions — any one suffices to block:
     * <ol>
     *   <li>{@code creditStatus IN (ON_HOLD, STOPPED)} — operator or automated status change.</li>
     *   <li>{@code manualHold = true} — credit-control staff override.</li>
     *   <li>AR balance + SO gross total exceeds the customer's credit limit.</li>
     * </ol>
     *
     * <p>WARNING is advisory only and never blocks (the operator sees it in the response DTO but
     * the order proceeds). Cash/walk-in customers bypass this check entirely.
     *
     * <p>If the caller holds {@code SALES.CREDIT.OVERRIDE}, the block is lifted and the override
     * is audited (mirrors SalesInvoiceServiceImpl.finalise credit-limit branch).
     */
    private void assertCreditClearance(SalesOrder order) {
        Customer customer = customers.findById(order.getCustomerId())
                .orElseThrow(() -> new NotFoundException(
                        "The customer linked to this sales order could not be found."));

        if (customer.getCustomerKind() != CustomerKind.CREDIT_ACCOUNT) {
            // Cash / walk-in: no credit restriction (BR-SO-CREDIT-01).
            return;
        }

        RequestContext.Principal ctx = RequestContext.get();

        // --- Block condition 1 & 2: status hold or manual hold ---
        CreditStatus cs = customer.getCreditStatus();
        boolean statusBlocked = cs == CreditStatus.ON_HOLD || cs == CreditStatus.STOPPED;
        boolean manualBlocked = customer.isManualHold();

        // --- Block condition 3: credit-limit breach ---
        boolean limitBreached = false;
        BigDecimal projectedBalance = null;
        Money creditLimit = customer.getCreditLimit();
        if (creditLimit != null && creditLimit.isPresent()
                && creditLimit.getAmount().compareTo(BigDecimal.ZERO) > 0) {
            ArBalanceDto balance = arBalanceService.currentBalance(
                    order.getCompanyId(), order.getCustomerId());
            projectedBalance = balance.balance().add(order.getGrossTotalAmount());
            if (projectedBalance.compareTo(creditLimit.getAmount()) > 0) {
                limitBreached = true;
            }
        }

        if (!statusBlocked && !manualBlocked && !limitBreached) {
            // No block condition met — proceed normally.
            return;
        }

        // At least one block condition is active. Check for override permission.
        boolean hasOverride = permissionResolver.hasPermission(
                ctx, "SALES.CREDIT.OVERRIDE", System.currentTimeMillis());

        if (!hasOverride) {
            // ADR-0040 D-5 / SALES.CREDIT.OVERRIDE permission required for override
            // Build a clear, actionable message covering whichever conditions fired.
            StringBuilder msg = new StringBuilder(
                    "This sales order is on hold due to a credit control restriction.");
            if (statusBlocked) {
                msg.append(" The customer's account is currently ");
                msg.append(cs == CreditStatus.STOPPED ? "stopped" : "on hold").append(".");
            }
            if (manualBlocked) {
                msg.append(" A manual credit hold is active");
                if (customer.getCreditHoldReason() != null) {
                    msg.append(": ").append(customer.getCreditHoldReason());
                }
                msg.append(".");
            }
            if (limitBreached && creditLimit != null) {
                msg.append(" Confirming this order would exceed the customer's credit limit.");
            }
            msg.append(" You do not have permission to override the credit restriction.");
            throw new ConflictException(msg.toString());
        }

        // Override granted — audit the bypass.
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("customerUid", customer.getUid());
        detail.put("creditStatus", cs.name());
        if (manualBlocked) detail.put("manualHold", "true");
        if (limitBreached && creditLimit != null && projectedBalance != null) {
            detail.put("creditLimit", creditLimit.getAmount().toPlainString());
            detail.put("creditLimitCurrency", CurrencyCode.value(creditLimit.getCurrency()));
            detail.put("projectedBalance", projectedBalance.toPlainString());
        }
        audit.record(AuditEvent.of(AuditActions.SALES_CREDIT_OVERRIDE, "sales_orders",
                order.getId(), order.getUid())
                .detail(detail));
    }

    private void recomputeTotals(SalesOrder order) {
        List<SalesOrderLine> lines = orderLines.findBySalesOrderIdOrderByLineNo(order.getId());
        totalsCalc.recompute(order, lines);
        order.setUpdatedAt(Instant.now());
        order.setUpdatedBy(actorId());
    }

    private SalesOrderDto toDto(SalesOrder o) {
        List<SalesOrderLineDto> lines = orderLines.findBySalesOrderIdOrderByLineNo(o.getId())
                .stream().map(SalesOrderLineDto::from).toList();
        return buildDto(o, lines);
    }

    private SalesOrderDto toDtoNoLines(SalesOrder o) {
        return buildDto(o, List.of());
    }

    /**
     * Resolves customer/agent/branch names at read time (mirrors SalesInvoiceServiceImpl.toDto).
     * agentId is nullable on SalesOrder (unlike SalesInvoice), so it is guarded before lookup.
     * Branch lookup is defensive: a missing row (should not happen) yields null names rather than
     * failing the read.
     */
    private SalesOrderDto buildDto(SalesOrder o, List<SalesOrderLineDto> lines) {
        String customerName = null;
        String customerCode = null;
        Customer customer = customers.findById(o.getCustomerId()).orElse(null);
        if (customer != null) {
            customerName = customer.getDisplayName();
            customerCode = customer.getCode();
        }
        String agentName = o.getAgentId() != null
                ? agents.findById(o.getAgentId()).map(Agent::getDisplayName).orElse(null)
                : null;
        String branchName = null;
        String branchCode = null;
        Branch branch = branches.findById(o.getBranchId()).orElse(null);
        if (branch != null) {
            branchName = branch.getName();
            branchCode = branch.getCode();
        }
        // Engine-derived, not persisted (D-7 seam): null when never submitted for approval.
        String approvalStatus = approvalEngine
                .getApprovalState(APPROVAL_DOC_TYPE, o.getUid(), o.getCompanyId())
                .map(r -> r.status().name())
                .orElse(null);
        return SalesOrderDto.from(o, lines, customerName, customerCode, agentName,
                branchName, branchCode, approvalStatus);
    }

    private Long requireBranchId(RequestContext.Principal ctx) {
        if (ctx == null || ctx.branchId() == null) {
            throw new IllegalStateException("No active branch in context.");
        }
        return ctx.branchId();
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
