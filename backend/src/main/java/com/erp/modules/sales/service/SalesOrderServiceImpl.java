package com.erp.modules.sales.service;

import com.erp.modules.ar.domain.dto.ArBalanceDto;
import com.erp.modules.ar.service.ArBalanceService;
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

    private final SalesOrderRepository     orders;
    private final SalesOrderLineRepository orderLines;
    private final QuotationRepository      quotations;
    private final QuotationLineRepository  quotationLines;
    private final CustomerRepository       customers;
    private final CustomerAddressRepository customerAddresses;
    private final PaymentTermsRepository   paymentTermsRepo;
    private final AgentRepository          agents;
    private final CompanyRepository        companies;
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

    public SalesOrderServiceImpl(SalesOrderRepository orders,
                                 SalesOrderLineRepository orderLines,
                                 QuotationRepository quotations,
                                 QuotationLineRepository quotationLines,
                                 CustomerRepository customers,
                                 CustomerAddressRepository customerAddresses,
                                 PaymentTermsRepository paymentTermsRepo,
                                 AgentRepository agents,
                                 CompanyRepository companies,
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
                                 PermissionResolver permissionResolver) {
        this.orders           = orders;
        this.orderLines       = orderLines;
        this.quotations       = quotations;
        this.quotationLines   = quotationLines;
        this.customers        = customers;
        this.customerAddresses = customerAddresses;
        this.paymentTermsRepo = paymentTermsRepo;
        this.agents           = agents;
        this.companies        = companies;
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
        SalesOrderLine line = orderLines.findByUidAndSalesOrderId(lineUid, order.getId())
                .orElseThrow(() -> new NotFoundException("SalesOrderLine not found: " + lineUid));
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
        if (order.getStatus() == SalesOrderStatus.CANCELLED
                || order.getStatus() == SalesOrderStatus.CLOSED) {
            throw new IllegalStateException(
                    "Cannot cancel an order in status " + order.getStatus());
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
    public SalesOrderDto createFromQuotation(String quotationUid) {
        Quotation quote = quotations.findByUid(quotationUid)
                .orElseThrow(() -> new NotFoundException("Quotation not found: " + quotationUid));

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
                .orElseThrow(() -> new NotFoundException("SalesOrder not found: " + salesOrderId));
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

    private Long resolveCompanyId(String uid) {
        return companies.findByUid(uid).map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found: " + uid));
    }

    private Customer resolveCustomer(Long companyId, String uid) {
        return customers.findByCompanyIdAndUid(companyId, uid)
                .orElseThrow(() -> new NotFoundException("Customer not found: " + uid));
    }

    /**
     * ADR-0041 D1 — resolves the PaymentTerms id for an order: the request uid takes priority,
     * else the customer's default {@code payment_terms_id}. Returns null when neither resolves.
     */
    private Long resolvePaymentTermsId(String paymentTermsUid, Customer customer) {
        if (paymentTermsUid != null && !paymentTermsUid.isBlank()) {
            return paymentTermsRepo.findByUid(paymentTermsUid)
                    .map(pt -> pt.getId())
                    .orElseThrow(() -> new NotFoundException("PaymentTerms not found: " + paymentTermsUid));
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
                .orElseThrow(() -> new NotFoundException("Customer address not found: " + addressUid));
        if (!addr.getCustomerId().equals(customerId)) {
            throw new ConflictException(
                    "Customer address " + addressUid + " does not belong to customer id=" + customerId);
        }
        return addr;
    }

    private Long resolveAgentId(Long companyId, String agentUid, RequestContext.Principal ctx) {
        if (agentUid != null && !agentUid.isBlank()) {
            return agents.findByCompanyIdAndUid(companyId, agentUid)
                    .map(Agent::getId)
                    .orElseThrow(() -> new NotFoundException("Agent not found: " + agentUid));
        }
        if (ctx != null && ctx.userId() != null) {
            Optional<Long> auto = agents.findInternalAgentIdByCompanyAndUser(companyId, ctx.userId());
            if (auto.isPresent()) return auto.get();
        }
        return null;
    }

    private Product resolveProduct(Long companyId, String uid) {
        return products.findByCompanyIdAndUid(companyId, uid)
                .orElseThrow(() -> new NotFoundException("Product not found: " + uid));
    }

    private void assertSellable(Product product) {
        if (!product.isSellable() || product.getStatus() == MasterStatus.ARCHIVED) {
            throw new IllegalArgumentException("Product not sellable: " + product.getUid());
        }
    }

    private UnitOfMeasure resolveUnit(Long companyId, String uid) {
        return units.findByCompanyIdAndUid(companyId, uid)
                .orElseThrow(() -> new NotFoundException("UnitOfMeasure not found: " + uid));
    }

    private BigDecimal resolveListPrice(Product product, Long companyId) {
        return prices.findByProductId(product.getId()).stream()
                .filter(p -> companyId.equals(p.getCompanyId()))
                .findFirst()
                .map(ProductPrice::getPrice)
                .filter(m -> m != null && m.getAmount() != null)
                .map(m -> m.getAmount())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product has no price for this company: " + product.getUid()));
    }

    private BigDecimal resolveVatRate(Long companyId, Product product) {
        return taxRates.findByCompanyIdAndVatStatus(companyId, product.getVatStatus())
                .map(r -> r.getRate())
                .orElseThrow(() -> new IllegalStateException(
                        "VAT rate not configured for " + product.getVatStatus()));
    }

    private BigDecimal computeQtyInBase(Product product, UnitOfMeasure unit, BigDecimal qty) {
        return bulkPacks.findByProductId(product.getId()).stream()
                .filter(bp -> bp.getUnit().getId().equals(unit.getId()))
                .findFirst()
                .map(bp -> qty.multiply(bp.getFactorToBase()))
                .orElse(qty);
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
                        "Customer not found for order " + order.getUid()
                                + " (id=" + order.getCustomerId() + ")"));

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
            // Build a clear, actionable message covering whichever conditions fired.
            StringBuilder msg = new StringBuilder("Sales order ")
                    .append(order.getOrderNumber())
                    .append(" blocked by credit control for customer ")
                    .append(customer.getUid())
                    .append(".");
            if (statusBlocked) {
                msg.append(" Credit status: ").append(cs.name()).append(".");
            }
            if (manualBlocked) {
                msg.append(" Manual hold is active");
                if (customer.getCreditHoldReason() != null) {
                    msg.append(" (").append(customer.getCreditHoldReason()).append(")");
                }
                msg.append(".");
            }
            if (limitBreached && creditLimit != null) {
                msg.append(" Credit limit ").append(creditLimit.getAmount().toPlainString())
                   .append(" ").append(CurrencyCode.value(creditLimit.getCurrency()))
                   .append(" exceeded; projected balance: ")
                   .append(projectedBalance.toPlainString()).append(".");
            }
            msg.append(" Requires SALES.CREDIT.OVERRIDE permission (ADR-0040 D-5).");
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
        return SalesOrderDto.from(o, lines);
    }

    private SalesOrderDto toDtoNoLines(SalesOrder o) {
        return SalesOrderDto.from(o, List.of());
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
