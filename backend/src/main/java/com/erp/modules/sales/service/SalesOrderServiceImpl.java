package com.erp.modules.sales.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerRepository;
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
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SalesOrderServiceImpl implements SalesOrderService {

    private final SalesOrderRepository     orders;
    private final SalesOrderLineRepository orderLines;
    private final QuotationRepository      quotations;
    private final QuotationLineRepository  quotationLines;
    private final CustomerRepository       customers;
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

    public SalesOrderServiceImpl(SalesOrderRepository orders,
                                 SalesOrderLineRepository orderLines,
                                 QuotationRepository quotations,
                                 QuotationLineRepository quotationLines,
                                 CustomerRepository customers,
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
                                 AuditService audit) {
        this.orders           = orders;
        this.orderLines       = orderLines;
        this.quotations       = quotations;
        this.quotationLines   = quotationLines;
        this.customers        = customers;
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
    }

    @Override
    public SalesOrderDto create(CreateSalesOrderRequest req) {
        Long companyId = resolveCompanyId(req.companyUid());
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, companyId);
        Long branchId = requireBranchId(ctx);

        Customer customer = resolveCustomer(companyId, req.customerUid());
        Long agentId = resolveAgentId(companyId, req.agentUid(), ctx);

        SalesOrder order = new SalesOrder(companyId, branchId, customer.getId(), agentId,
                req.currency(), req.orderDate(), actorId());
        order.setDocDiscountAmount(req.docDiscountAmount());
        order.setDocDiscountPercent(req.docDiscountPercent());
        order.setNotes(req.notes());
        order.setOrderNumber(numberGen.nextSalesOrder(companyId));

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
        BigDecimal appliedPrice = req.unitPriceOverride() != null ? req.unitPriceOverride() : listPrice;
        BigDecimal vatRate = resolveVatRate(order.getCompanyId(), product);
        BigDecimal qtyInBase = computeQtyInBase(product, unit, req.quantity());
        short lineNo = (short) (orderLines.findMaxLineNo(order.getId()) + 1);

        SalesOrderLine line = new SalesOrderLine(
                order.getId(), order.getCompanyId(), order.getBranchId(), lineNo,
                product.getId(), product.getCode(), product.getName(),
                unit.getId(), unit.getName(),
                req.quantity(), qtyInBase,
                listPrice, appliedPrice,
                product.getVatStatus(), vatRate,
                order.getCurrency(), actorId());
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
        SalesOrder order = require(orderUid);
        scopeGuard.assertCanActIn(RequestContext.get(), order.getCompanyId());
        assertDraft(order);

        List<SalesOrderLine> lines = orderLines.findBySalesOrderIdOrderByLineNo(order.getId());
        if (lines.isEmpty()) {
            throw new IllegalStateException("Cannot confirm a sales order with no lines.");
        }

        // Reserve stock for each line (D-4/D-5)
        for (SalesOrderLine line : lines) {
            line.setQtyReservedBase(line.getQtyOrderedBase());
            line.setUpdatedAt(Instant.now());
            line.setUpdatedBy(actorId());
            reservationService.applyReservationDelta(
                    order.getCompanyId(), order.getBranchId(),
                    line.getProductId(), line.getQtyOrderedBase(), actorId());
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
                quote.getCurrency(), LocalDate.now(), actorId());
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
                    ql.getCurrency(), actorId());
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
