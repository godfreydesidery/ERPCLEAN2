package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.CreateDeliveryRequest;
import com.erp.modules.sales.domain.dto.DeliveryConfirmedPayload;
import com.erp.modules.sales.domain.dto.DeliveryDto;
import com.erp.modules.sales.domain.dto.DeliveryLineDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.entity.Delivery;
import com.erp.modules.sales.domain.entity.DeliveryLine;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.entity.SalesInvoiceLine;
import com.erp.modules.sales.domain.entity.SalesOrder;
import com.erp.modules.sales.domain.entity.SalesOrderLine;
import com.erp.modules.sales.domain.enums.DocumentOrigin;
import com.erp.modules.sales.domain.enums.SalesOrderStatus;
import com.erp.modules.sales.repository.DeliveryLineRepository;
import com.erp.modules.sales.repository.DeliveryRepository;
import com.erp.modules.sales.repository.SalesInvoiceLineRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.modules.sales.repository.SalesOrderLineRepository;
import com.erp.modules.sales.repository.SalesOrderRepository;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.stock.service.StockReservationService;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delivery lifecycle service (ADR-0021 D-7/D-10).
 *
 * <p>Deliveries are created directly in CONFIRMED status (picking/packing deferred).
 * On create: releases the per-line reservation, increments SO fulfilled qty, publishes
 * DELIVERY.CONFIRMED in the same TX, then calls salesOrderService.recomputeStatus.
 *
 * <p>D-10 seam: createInvoiceFromDelivery copies delivery lines onto a DRAFT SalesInvoice
 * with origin=SALES_ORDER so that SaleIssueStockHandler will skip it at finalise — the
 * delivery has already issued the stock.
 */
@Service
@Transactional
public class DeliveryServiceImpl implements DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryServiceImpl.class);

    private final DeliveryRepository         deliveries;
    private final DeliveryLineRepository     deliveryLines;
    private final SalesOrderRepository       salesOrders;
    private final SalesOrderLineRepository   salesOrderLines;
    private final SalesInvoiceRepository     invoices;
    private final SalesInvoiceLineRepository invoiceLines;
    private final StockReservationService    reservationService;
    private final OrderToCashNumberGenerator numberGen;
    private final SalesOrderService          salesOrderService;
    private final InvoiceTotalsCalculator    totalsCalc;
    private final ScopeGuard                 scopeGuard;
    private final AuditService               audit;
    private final OutboxPublisher            outbox;
    /**
     * Used to resolve product uid for the DELIVERY.CONFIRMED payload (ADR-0021 D-6 fix).
     * SalesOrderLine stores only productId; the handler needs productUid for recipe explosion.
     */
    private final ProductRepository          productRepository;
    /** Owner decision 2026-07-05 (V87): synchronous "block negative stock on sale" pre-check. */
    private final NegativeStockGuard         negativeStockGuard;

    public DeliveryServiceImpl(DeliveryRepository deliveries,
                               DeliveryLineRepository deliveryLines,
                               SalesOrderRepository salesOrders,
                               SalesOrderLineRepository salesOrderLines,
                               SalesInvoiceRepository invoices,
                               SalesInvoiceLineRepository invoiceLines,
                               StockReservationService reservationService,
                               OrderToCashNumberGenerator numberGen,
                               SalesOrderService salesOrderService,
                               InvoiceTotalsCalculator totalsCalc,
                               ScopeGuard scopeGuard,
                               AuditService audit,
                               OutboxPublisher outbox,
                               ProductRepository productRepository,
                               NegativeStockGuard negativeStockGuard) {
        this.deliveries        = deliveries;
        this.deliveryLines     = deliveryLines;
        this.salesOrders       = salesOrders;
        this.salesOrderLines   = salesOrderLines;
        this.invoices          = invoices;
        this.invoiceLines      = invoiceLines;
        this.reservationService = reservationService;
        this.numberGen         = numberGen;
        this.salesOrderService = salesOrderService;
        this.totalsCalc        = totalsCalc;
        this.scopeGuard        = scopeGuard;
        this.audit             = audit;
        this.outbox            = outbox;
        this.productRepository = productRepository;
        this.negativeStockGuard = negativeStockGuard;
    }

    // -------------------------------------------------------------------------
    // create — the central O2C step
    // -------------------------------------------------------------------------

    @Override
    public DeliveryDto create(CreateDeliveryRequest req) {
        try {
            return doCreate(req);
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.warn("DeliveryService.create: optimistic lock conflict on SO line — retrying once");
            return doCreate(req);
        }
    }

    private DeliveryDto doCreate(CreateDeliveryRequest req) {
        SalesOrder order = requireOrder(req.salesOrderUid());
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, order.getCompanyId());

        if (order.getStatus() != SalesOrderStatus.CONFIRMED
                && order.getStatus() != SalesOrderStatus.PARTIALLY_FULFILLED) {
            throw new IllegalStateException(
                    "Can only deliver against a CONFIRMED or PARTIALLY_FULFILLED order; current: "
                            + order.getStatus());
        }

        Delivery delivery = new Delivery(
                order.getCompanyId(), order.getBranchId(),
                order.getId(), order.getUid(),
                order.getCustomerId(), req.deliveryDate(), actorId());
        delivery.setDeliveryNumber(numberGen.nextDelivery(order.getCompanyId()));
        delivery.setNotes(req.notes());
        // ADR-0041 D2 — inherit the ship-to address snapshot from the source sales order (immutable).
        delivery.setShipToAddressId(order.getShipToAddressId());
        delivery.setShipToAddressText(order.getShipToAddressText());
        Delivery saved = deliveries.save(delivery);

        List<DeliveryLine> savedLines = new ArrayList<>();
        List<DeliveryConfirmedPayload.LineItem> payloadLines = new ArrayList<>();
        short lineNo = 1;

        for (CreateDeliveryRequest.DeliveryLineRequest lineReq : req.lines()) {
            SalesOrderLine sol = salesOrderLines.findByUid(lineReq.salesOrderLineUid())
                    .orElseThrow(() -> new NotFoundException(
                            "Sales order line not found."));

            if (!sol.getSalesOrderId().equals(order.getId())) {
                throw new IllegalArgumentException(
                        "One or more delivery lines do not belong to the specified sales order.");
            }

            BigDecimal qtyDeliveredBase = lineReq.qtyDelivered();  // UI sends base qty; unit=base in v1
            if (qtyDeliveredBase.compareTo(BigDecimal.ZERO) <= 0) {
                // sol.getUid() intentionally not surfaced (error-hygiene rule)
                throw new IllegalArgumentException(
                        "Delivery quantity must be greater than zero.");
            }

            // BR-SO-11: cannot deliver more than the open (unfulfilled) qty on the SO line
            BigDecimal openQty = sol.getQtyOrderedBase().subtract(sol.getQtyFulfilledBase());
            if (qtyDeliveredBase.compareTo(openQty) > 0) {
                // BR-SO-11: cannot deliver more than the outstanding (unfulfilled) qty
                throw new IllegalStateException(
                        "The delivery quantity (" + qtyDeliveredBase + ") exceeds the remaining "
                                + "quantity available to deliver (" + openQty + ") for this order line.");
            }

            // Release the corresponding reservation (delta = negative) BEFORE the negative-stock
            // guard below, so the guard's availability read reflects this line's own release rather
            // than double-counting a reservation this very delivery is about to consume — otherwise
            // every normal fully-reserved SO delivery would false-block (e.g. DeliveryServiceIT
            // partialDelivery_postsCogs_releasesReservation_backorderRemains: on-hand 10, reserved
            // 10 from confirm, deliver 4 — available-before-release would read 0, not the true 4).
            BigDecimal releaseAmt = qtyDeliveredBase.min(sol.getQtyReservedBase());
            if (releaseAmt.compareTo(BigDecimal.ZERO) > 0) {
                reservationService.applyReservationDelta(
                        order.getCompanyId(), order.getBranchId(),
                        sol.getProductId(), releaseAmt.negate(), actorId());
                sol.setQtyReservedBase(sol.getQtyReservedBase().subtract(releaseAmt));
            }

            // Resolve the product once — needed for both the negative-stock guard below and the
            // DELIVERY.CONFIRMED payload uid (ADR-0021 D-6 fix; SalesOrderLine stores only productId).
            // Kept as the pre-existing by-id lookup (already an audited/frozen TenantScopingRulesTest
            // exception at this call site — sol.getProductId() is not caller-supplied, it was already
            // resolved+scoped when the SO line was added) — not re-scoped here to avoid churn
            // unrelated to this feature.
            Product product = productRepository.findById(sol.getProductId())
                    .orElseThrow(() -> new NotFoundException("Product not found."));

            // Owner decision 2026-07-05 (V87, sales_settings.allow_negative_stock): synchronous
            // negative-stock pre-check at the delivery-create issue path — see NegativeStockGuard
            // javadoc (this IS the enforcement point; DeliveryIssueStockHandler runs later, async,
            // and does not re-check).
            negativeStockGuard.assertAvailable(
                    order.getCompanyId(), order.getBranchId(),
                    product.getId(), product.getUid(), product.isStockable(),
                    sol.getProductName(), qtyDeliveredBase);

            DeliveryLine dl = new DeliveryLine(
                    saved.getId(), sol.getId(), sol.getUid(),
                    saved.getCompanyId(), saved.getBranchId(), lineNo++,
                    sol.getProductId(), sol.getProductCode(), sol.getProductName(),
                    sol.getUnitId(), sol.getUnitName(),
                    qtyDeliveredBase, qtyDeliveredBase,   // qty + qty_base (same in v1)
                    sol.getCurrency().value(), actorId());
            savedLines.add(deliveryLines.save(dl));

            // Update fulfilled qty on SO line
            sol.setQtyFulfilledBase(sol.getQtyFulfilledBase().add(qtyDeliveredBase));
            sol.setUpdatedAt(Instant.now());
            sol.setUpdatedBy(actorId());

            payloadLines.add(new DeliveryConfirmedPayload.LineItem(
                    sol.getProductId(), product.getUid(),
                    sol.getUnitId(), qtyDeliveredBase,
                    dl.getId()));
        }

        // Publish DELIVERY.CONFIRMED in the SAME transaction (ADR-0021 D-6)
        outbox.publish(
                DomainEventType.DELIVERY_CONFIRMED,
                DomainEventType.AGG_DELIVERY,
                saved.getId(), saved.getUid(),
                saved.getCompanyId(), saved.getBranchId(),
                new DeliveryConfirmedPayload(
                        saved.getUid(), order.getUid(),
                        saved.getCompanyId(), saved.getBranchId(),
                        saved.getConfirmedAt(), payloadLines,
                        saved.getDeliveryNumber()));

        // Recompute SO status (may transition to PARTIALLY_FULFILLED or FULFILLED)
        salesOrderService.recomputeStatus(order.getId());

        audit.record(AuditEvent.of(AuditActions.DELIVERY_CREATE, "deliveries",
                saved.getId(), saved.getUid())
                .detail(Map.of("deliveryNumber", saved.getDeliveryNumber(),
                        "salesOrderUid", order.getUid())));

        return toDto(saved, savedLines);
    }

    // -------------------------------------------------------------------------
    // read paths
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public DeliveryDto getByUid(String uid) {
        Delivery d = requireDelivery(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), d.getCompanyId());
        return toDto(d, deliveryLines.findByDeliveryIdOrderByLineNo(d.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DeliveryDto> list(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return deliveries.findByCompanyId(companyId, pageable).map(d ->
                toDto(d, deliveryLines.findByDeliveryIdOrderByLineNo(d.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryDto> listForOrder(String salesOrderUid) {
        SalesOrder order = requireOrder(salesOrderUid);
        scopeGuard.assertCanActIn(RequestContext.get(), order.getCompanyId());
        return deliveries.findBySalesOrderId(order.getId()).stream().map(d ->
                toDto(d, deliveryLines.findByDeliveryIdOrderByLineNo(d.getId()))).toList();
    }

    // -------------------------------------------------------------------------
    // D-10 seam: createInvoiceFromDelivery
    // -------------------------------------------------------------------------

    /**
     * Creates a DRAFT SalesInvoice from the uninvoiced lines on this delivery (ADR-0021 D-10).
     *
     * <p>The invoice has origin=SALES_ORDER so that when the caller finalises it,
     * SaleIssueStockHandler will skip stock issue (delivery already issued the stock).
     * qty_invoiced_base is updated on delivery lines and SO lines in this TX.
     */
    @Override
    public SalesInvoiceDto createInvoiceFromDelivery(String deliveryUid) {
        Delivery delivery = requireDelivery(deliveryUid);
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, delivery.getCompanyId());

        SalesOrder order = salesOrders.findById(delivery.getSalesOrderId())
                .orElseThrow(() -> new NotFoundException(
                        "Sales order not found."));

        List<DeliveryLine> dLines = deliveryLines.findByDeliveryIdOrderByLineNo(delivery.getId());
        List<DeliveryLine> billable = dLines.stream()
                .filter(l -> l.openInvoiceQtyBase().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        if (billable.isEmpty()) {
            // deliveryUid intentionally not surfaced (error-hygiene rule)
            throw new IllegalStateException(
                    "This delivery has no uninvoiced lines.");
        }

        // Resolve the agent from the SO (the SO carries agentId from order creation)
        Long agentId = order.getAgentId();
        if (agentId == null) {
            throw new IllegalStateException("The sales order has no agent assigned.");
        }

        // D-9 / FIX-4: if the SO carries a fixed docDiscountAmount, pro-rate it to the
        // invoiced (delivered) subset so the partial invoice's VAT is on the correct
        // discounted net, not the full-order discount applied to fewer lines.
        // docDiscountPercent is copied verbatim — same rate gives the correct per-line share.
        BigDecimal invoiceDocDiscountAmount = order.getDocDiscountAmount();
        if (invoiceDocDiscountAmount != null
                && invoiceDocDiscountAmount.compareTo(BigDecimal.ZERO) > 0) {
            // Compute SO total raw net (unitPrice × qty − lineDiscount, floored at 0) for ratio.
            List<SalesOrderLine> allSoLines =
                    salesOrderLines.findBySalesOrderIdOrderByLineNo(order.getId());
            BigDecimal soRawNetSum = allSoLines.stream()
                    .map(sol -> {
                        BigDecimal gross = sol.getUnitPriceAmount()
                                .multiply(sol.getQtyOrderedBase());
                        BigDecimal dis = sol.getLineDiscountAmount() != null
                                && sol.getLineDiscountAmount().compareTo(BigDecimal.ZERO) > 0
                                ? sol.getLineDiscountAmount()
                                : sol.getLineDiscountPercent() != null
                                && sol.getLineDiscountPercent().compareTo(BigDecimal.ZERO) > 0
                                ? gross.multiply(sol.getLineDiscountPercent())
                                .divide(BigDecimal.valueOf(100), 4,
                                        RoundingMode.HALF_UP)
                                : BigDecimal.ZERO;
                        return gross.subtract(dis).max(BigDecimal.ZERO);
                    })
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            // Compute the invoiced-lines' raw net sum (use qty-to-invoice for each billable line).
            // We need the SOL for each billable line to get pricing — resolve inline.
            BigDecimal invRawNetSum = BigDecimal.ZERO;
            for (DeliveryLine dl : billable) {
                SalesOrderLine sol = salesOrderLines.findById(dl.getSalesOrderLineId())
                        .orElseThrow(() -> new NotFoundException(
                                "Sales order line not found."));
                BigDecimal qty = dl.openInvoiceQtyBase();
                BigDecimal gross = sol.getUnitPriceAmount().multiply(qty);
                BigDecimal dis = sol.getLineDiscountAmount() != null
                        && sol.getLineDiscountAmount().compareTo(BigDecimal.ZERO) > 0
                        ? sol.getLineDiscountAmount()
                        : sol.getLineDiscountPercent() != null
                        && sol.getLineDiscountPercent().compareTo(BigDecimal.ZERO) > 0
                        ? gross.multiply(sol.getLineDiscountPercent())
                        .divide(java.math.BigDecimal.valueOf(100), 4,
                                java.math.RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                invRawNetSum = invRawNetSum.add(gross.subtract(dis).max(BigDecimal.ZERO));
            }
            if (soRawNetSum.compareTo(BigDecimal.ZERO) > 0) {
                invoiceDocDiscountAmount = invoiceDocDiscountAmount
                        .multiply(invRawNetSum)
                        .divide(soRawNetSum, 4, RoundingMode.HALF_UP);
            }
        }

        SalesInvoice invoice = new SalesInvoice(
                delivery.getCompanyId(), delivery.getBranchId(),
                delivery.getCustomerId(), agentId,
                order.getCurrency().value(), actorId());
        invoice.setOrigin(DocumentOrigin.SALES_ORDER);
        invoice.setSourceOrderUid(order.getUid());
        invoice.setSourceDeliveryUid(delivery.getUid());
        // ADR-0041 D1/D2 — a SALES_ORDER-origin invoice inherits the SO's payment terms and the
        // ship-to / bill-to address ids + snapshots (immutable).
        invoice.setPaymentTermsId(order.getPaymentTermsId());
        invoice.setShipToAddressId(order.getShipToAddressId());
        invoice.setShipToAddressText(order.getShipToAddressText());
        invoice.setBillToAddressId(order.getBillToAddressId());
        invoice.setBillToAddressText(order.getBillToAddressText());
        invoice.setDocDiscountAmount(invoiceDocDiscountAmount);
        // Percent is copied verbatim: same rate × invoiced lines' raw net = correct share
        invoice.setDocDiscountPercent(order.getDocDiscountPercent());

        SalesInvoice savedInv = invoices.save(invoice);
        List<SalesInvoiceLine> createdLines = new ArrayList<>();
        short lineNo = 1;

        for (DeliveryLine dl : billable) {
            SalesOrderLine sol = salesOrderLines.findById(dl.getSalesOrderLineId())
                    .orElseThrow(() -> new NotFoundException(
                            "Sales order line not found."));

            BigDecimal qtyToInvoice = dl.openInvoiceQtyBase();

            SalesInvoiceLine invLine = new SalesInvoiceLine(
                    savedInv, lineNo++,
                    sol.getProductId(), sol.getProductCode(), sol.getProductName(),
                    sol.getUnitId(), sol.getUnitName(),
                    qtyToInvoice, qtyToInvoice,       // quantity + qty_in_base
                    sol.getListPriceAmount(), sol.getUnitPriceAmount(),
                    sol.getVatStatus(), sol.getVatRate(),
                    actorId());
            invLine.setLineDiscountAmount(sol.getLineDiscountAmount());
            invLine.setLineDiscountPercent(sol.getLineDiscountPercent());
            // Carry the VAT-inclusive/exclusive stance snapshot from the SO line, else the
            // totals recompute below would treat an inclusive (gross) unit price as net and
            // re-add VAT — over-taxing the posted invoice (ADR-0056).
            invLine.setPriceInclusive(sol.isPriceInclusive());
            createdLines.add(invoiceLines.save(invLine));

            // Update running counters on delivery line and SO line
            dl.setQtyInvoicedBase(dl.getQtyInvoicedBase().add(qtyToInvoice));
            dl.setUpdatedAt(Instant.now());
            dl.setUpdatedBy(actorId());

            sol.setQtyInvoicedBase(sol.getQtyInvoicedBase().add(qtyToInvoice));
            sol.setUpdatedAt(Instant.now());
            sol.setUpdatedBy(actorId());
        }

        // Compute totals on the new invoice
        totalsCalc.recompute(savedInv, createdLines);

        // Recompute SO status (may move to PARTIALLY_INVOICED / CLOSED)
        salesOrderService.recomputeStatus(order.getId());

        audit.record(AuditEvent.of(AuditActions.DELIVERY_INVOICE, "deliveries",
                delivery.getId(), delivery.getUid())
                .detail(Map.of("invoiceUid", savedInv.getUid(),
                        "deliveryUid", deliveryUid)));

        return SalesInvoiceDto.from(savedInv, null, null, null, null, null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Delivery requireDelivery(String uid) {
        return Lookups.orNotFound(deliveries.findByUid(uid), "Delivery", uid);
    }

    private SalesOrder requireOrder(String uid) {
        return Lookups.orNotFound(salesOrders.findByUid(uid), "SalesOrder", uid);
    }

    private DeliveryDto toDto(Delivery d, List<DeliveryLine> lines) {
        return DeliveryDto.from(d, lines.stream().map(DeliveryLineDto::from).toList());
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
