package com.erp.modules.sales.service;

import com.erp.modules.ar.domain.dto.ArCreditNoteDto;
import com.erp.modules.ar.domain.dto.RaiseCreditNoteRequest;
import com.erp.modules.ar.domain.enums.ArCreditNoteOrigin;
import com.erp.modules.ar.service.ArCreditNoteService;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.sales.domain.dto.CreateSalesReturnRequest;
import com.erp.modules.sales.domain.dto.DeliveryReturnedPayload;
import com.erp.modules.sales.domain.dto.SalesReturnDto;
import com.erp.modules.sales.domain.dto.SalesReturnLineDto;
import com.erp.modules.sales.domain.entity.Delivery;
import com.erp.modules.sales.domain.entity.DeliveryLine;
import com.erp.modules.sales.domain.entity.SalesOrder;
import com.erp.modules.sales.domain.entity.SalesOrderLine;
import com.erp.modules.sales.domain.entity.SalesReturn;
import com.erp.modules.sales.domain.entity.SalesReturnLine;
import com.erp.modules.sales.repository.DeliveryLineRepository;
import com.erp.modules.sales.repository.DeliveryRepository;
import com.erp.modules.sales.repository.SalesOrderLineRepository;
import com.erp.modules.sales.repository.SalesOrderRepository;
import com.erp.modules.sales.repository.SalesReturnLineRepository;
import com.erp.modules.sales.repository.SalesReturnRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyMinorUnits;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sales return / RMA service (ADR-0021 D-11, Stage 2).
 *
 * <p>Stock-in + COGS reversal are done via the DELIVERY.RETURNED outbox event (symmetry with
 * DELIVERY.CONFIRMED, crash-safe + idempotent — NFR-SO-06).
 * The credit note is raised synchronously in this TX (same as the invoice path).
 *
 * <p>Cost basis (OQ-SO-05): for each return line the original issued cost is read from
 * {@code delivery_line.issue_value_amount} and pro-rated for partial returns:
 * {@code originalValue = issueValueAmount × (qtyReturnedBase / qtyDeliveredBase)}, HALF_UP 4 dp.
 */
@Service
@Transactional
public class SalesReturnServiceImpl implements SalesReturnService {

    private static final Logger log = LoggerFactory.getLogger(SalesReturnServiceImpl.class);

    private final SalesReturnRepository      returns;
    private final SalesReturnLineRepository  returnLines;
    private final DeliveryRepository         deliveries;
    private final DeliveryLineRepository     deliveryLines;
    private final SalesOrderRepository       salesOrders;
    private final SalesOrderLineRepository   salesOrderLines;
    private final CustomerRepository         customers;
    private final CompanyRepository          companies;
    private final ArCreditNoteService        creditNoteService;
    private final OrderToCashNumberGenerator numberGen;
    private final ScopeGuard                 scopeGuard;
    private final AuditService               audit;
    private final OutboxPublisher            outbox;
    private final CurrencyMinorUnits         minorUnits;

    public SalesReturnServiceImpl(SalesReturnRepository returns,
                                   SalesReturnLineRepository returnLines,
                                   DeliveryRepository deliveries,
                                   DeliveryLineRepository deliveryLines,
                                   SalesOrderRepository salesOrders,
                                   SalesOrderLineRepository salesOrderLines,
                                   CustomerRepository customers,
                                   CompanyRepository companies,
                                   ArCreditNoteService creditNoteService,
                                   OrderToCashNumberGenerator numberGen,
                                   ScopeGuard scopeGuard,
                                   AuditService audit,
                                   OutboxPublisher outbox,
                                   CurrencyMinorUnits minorUnits) {
        this.returns          = returns;
        this.returnLines      = returnLines;
        this.deliveries       = deliveries;
        this.deliveryLines    = deliveryLines;
        this.salesOrders      = salesOrders;
        this.salesOrderLines  = salesOrderLines;
        this.customers        = customers;
        this.companies        = companies;
        this.creditNoteService = creditNoteService;
        this.numberGen        = numberGen;
        this.scopeGuard       = scopeGuard;
        this.audit            = audit;
        this.outbox           = outbox;
        this.minorUnits       = minorUnits;
    }

    // -------------------------------------------------------------------------
    // create — the single-step confirmed return
    // -------------------------------------------------------------------------

    @Override
    public SalesReturnDto create(CreateSalesReturnRequest req) {
        Delivery delivery = requireDelivery(req.deliveryUid());
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, delivery.getCompanyId());

        // Load delivery lines into a map for O(1) lookup
        List<DeliveryLine> allDeliveryLines =
                deliveryLines.findByDeliveryIdOrderByLineNo(delivery.getId());
        Map<String, DeliveryLine> dlByUid = new HashMap<>();
        for (DeliveryLine dl : allDeliveryLines) {
            dlByUid.put(dl.getUid(), dl);
        }

        // Resolve SO for this delivery
        SalesOrder order = salesOrders.findById(delivery.getSalesOrderId())
                .orElseThrow(() -> new NotFoundException(
                        "No sales order was found for this delivery."));

        // Build the SalesReturn header (status=CONFIRMED, number allocated now)
        SalesReturn ret = new SalesReturn(
                delivery.getCompanyId(), delivery.getBranchId(),
                delivery.getId(), delivery.getUid(), order.getUid(),
                delivery.getCustomerId(),
                req.returnDate(), order.getCurrency().value(),
                req.reason(), actorId());
        ret.setReturnNumber(numberGen.nextSalesReturn(delivery.getCompanyId()));
        SalesReturn savedReturn = returns.save(ret);

        List<SalesReturnLine> savedLines    = new ArrayList<>();
        List<DeliveryReturnedPayload.ReturnLineItem> payloadLines = new ArrayList<>();
        BigDecimal totalNet   = BigDecimal.ZERO;
        BigDecimal totalVat   = BigDecimal.ZERO;
        BigDecimal totalGross = BigDecimal.ZERO;
        short lineNo = 1;
        // Credit-note amounts round in the ORDER currency's minor units (TZS 0 dp, USD 2 dp),
        // exactly as the invoice they reverse did.
        final int scale = minorUnits.of(order.getCurrency());

        for (CreateSalesReturnRequest.ReturnLineRequest lineReq : req.lines()) {
            DeliveryLine dl = dlByUid.get(lineReq.deliveryLineUid());
            if (dl == null) {
                throw new NotFoundException("Delivery line not found.");
            }
            if (!dl.getDeliveryId().equals(delivery.getId())) {
                throw new IllegalArgumentException(
                        "A delivery line does not belong to the specified delivery.");
            }

            BigDecimal qtyReturnedBase = lineReq.qtyReturned();
            if (qtyReturnedBase.compareTo(BigDecimal.ZERO) <= 0) {
                // dl.getUid() intentionally not surfaced (error-hygiene rule)
                throw new IllegalArgumentException(
                        "Return quantity must be greater than zero.");
            }

            // BR-SO-11: returned ≤ delivered − already_returned
            BigDecimal returnableQty = dl.getQtyDeliveredBase().subtract(dl.getReturnedQtyBase());
            if (qtyReturnedBase.compareTo(returnableQty) > 0) {
                // BR-SO-11: cannot return more than the outstanding returnable qty
                throw new IllegalStateException(
                        "The return quantity (" + qtyReturnedBase + ") exceeds the quantity "
                                + "available to return (" + returnableQty + ") for this delivery line.");
            }

            // OQ-SO-05: pro-rate original issued cost for this partial return
            BigDecimal originalIssueValue = proRateIssueValue(dl, qtyReturnedBase);

            // Resolve SO line for pricing snapshots (needed for credit-note amounts)
            SalesOrderLine sol = salesOrderLines.findById(dl.getSalesOrderLineId())
                    .orElseThrow(() -> new NotFoundException(
                            "Sales order line not found."));

            // Return line amounts = the order line's own net and VAT, pro-rated by base quantity
            // and rounded in the order currency (see proRateAmounts).
            BigDecimal vatRate   = sol.getVatRate() != null ? sol.getVatRate() : BigDecimal.ZERO;
            ReturnAmounts amounts = proRateAmounts(sol, qtyReturnedBase, scale);
            BigDecimal lineNet   = amounts.net();
            BigDecimal lineVat   = amounts.vat();
            BigDecimal lineGross = lineNet.add(lineVat);

            SalesReturnLine rl = new SalesReturnLine(
                    savedReturn.getId(),
                    dl.getId(), dl.getUid(),
                    delivery.getCompanyId(), delivery.getBranchId(), lineNo++,
                    dl.getProductId(), dl.getProductCode(), dl.getProductName(),
                    dl.getUnitId(), dl.getUnitName(),
                    qtyReturnedBase, qtyReturnedBase,          // qty + qty_base (same in v1)
                    sol.getUnitPriceAmount(),
                    proRateDiscountAmount(sol, qtyReturnedBase),
                    sol.getLineDiscountPercent(),
                    sol.getVatStatus() != null ? sol.getVatStatus() : com.erp.modules.products.domain.enums.VatStatus.STANDARD,
                    vatRate,
                    order.getCurrency().value(), actorId());
            rl.setNetAmount(lineNet);
            rl.setVatAmount(lineVat);
            rl.setGrossAmount(lineGross);
            savedLines.add(returnLines.save(rl));

            totalNet   = totalNet.add(lineNet);
            totalVat   = totalVat.add(lineVat);
            totalGross = totalGross.add(lineGross);

            // Increment returned_qty_base on the delivery line
            dl.setReturnedQtyBase(dl.getReturnedQtyBase().add(qtyReturnedBase));
            dl.setUpdatedAt(Instant.now());
            dl.setUpdatedBy(actorId());

            payloadLines.add(new DeliveryReturnedPayload.ReturnLineItem(
                    dl.getProductId(),
                    dl.getProductId().toString(),   // handler uses productId directly; uid is diagnostic
                    dl.getUnitId(),
                    qtyReturnedBase,
                    originalIssueValue));
        }

        // Update return header totals
        savedReturn.setNetAmount(totalNet);
        savedReturn.setVatAmount(totalVat);
        savedReturn.setGrossAmount(totalGross);
        savedReturn.setUpdatedAt(Instant.now());
        savedReturn.setUpdatedBy(actorId());

        // Publish DELIVERY.RETURNED in the SAME TX — stock-in + COGS reversal via handler
        outbox.publish(
                DomainEventType.DELIVERY_RETURNED,
                DomainEventType.AGG_SALES_RETURN,
                savedReturn.getId(), savedReturn.getUid(),
                delivery.getCompanyId(), delivery.getBranchId(),
                new DeliveryReturnedPayload(
                        savedReturn.getUid(), delivery.getUid(),
                        delivery.getCompanyId(), delivery.getBranchId(),
                        Instant.now(), payloadLines,
                        savedReturn.getReturnNumber()));

        // Raise credit note synchronously (origin=RETURN — ADR-0021 D-11)
        ArCreditNoteDto creditNote = raiseCreditNote(
                delivery, order, savedReturn, totalNet, totalVat);
        savedReturn.setCreditNoteUid(creditNote.uid());

        audit.record(AuditEvent.of(AuditActions.RETURN_CREATE, "sales_returns",
                savedReturn.getId(), savedReturn.getUid())
                .detail(Map.of(
                        "returnNumber", savedReturn.getReturnNumber(),
                        "deliveryUid", delivery.getUid(),
                        "creditNoteUid", creditNote.uid())));

        return SalesReturnDto.from(savedReturn,
                savedLines.stream().map(SalesReturnLineDto::from).toList());
    }

    // -------------------------------------------------------------------------
    // read paths
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public SalesReturnDto getByUid(String uid) {
        SalesReturn r = requireReturn(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), r.getCompanyId());
        return toDto(r);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SalesReturnDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return returns.findByCompanyId(companyId, pageable).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SalesReturnDto> listByDelivery(String deliveryUid, Pageable pageable) {
        Delivery delivery = requireDelivery(deliveryUid);
        scopeGuard.assertCanActIn(RequestContext.get(), delivery.getCompanyId());
        return returns.findByCompanyIdAndDeliveryId(
                delivery.getCompanyId(), delivery.getId(), pageable).map(this::toDto);
    }

    // -------------------------------------------------------------------------
    // Credit note
    // -------------------------------------------------------------------------

    private ArCreditNoteDto raiseCreditNote(Delivery delivery, SalesOrder order,
                                             SalesReturn savedReturn,
                                             BigDecimal netAmount, BigDecimal vatAmount) {
        String companyUid = companies.findById(delivery.getCompanyId())
                .map(c -> c.getUid())
                .orElseThrow(() -> new NotFoundException(
                        "Company not found."));

        String customerUid = customers.findById(delivery.getCustomerId())
                .map(Customer::getUid)
                .orElseThrow(() -> new NotFoundException(
                        "Customer not found."));

        // ADR-0021 D-11: if delivery not yet invoiced (no AR open item), raise as unapplied credit.
        // v1 always raises unapplied; the credit note reduces the balance when matched.
        return creditNoteService.raise(new RaiseCreditNoteRequest(
                companyUid,
                customerUid,
                null,                   // arInvoiceUid=null → unapplied credit (ADR-0021 D-11)
                savedReturn.getReturnDate(),
                netAmount,
                vatAmount,
                order.getCurrency().value(),
                savedReturn.getReason() != null
                        ? savedReturn.getReason()
                        : "Sales return " + savedReturn.getReturnNumber(),
                ArCreditNoteOrigin.RETURN));
    }

    // -------------------------------------------------------------------------
    // Pricing helpers
    // -------------------------------------------------------------------------

    /**
     * Pro-rates the original issued cost for a partial return (OQ-SO-05, ADR-0021 D-11).
     *
     * <p>{@code originalValue = issueValueAmount × (qtyReturnedBase / qtyDeliveredBase)}, HALF_UP.
     * Returns null when issue_value_amount is null (no COGS to reverse — avg not established).
     */
    private BigDecimal proRateIssueValue(DeliveryLine dl, BigDecimal qtyReturnedBase) {
        if (dl.getIssueValueAmount() == null
                || dl.getIssueValueAmount().compareTo(BigDecimal.ZERO) == 0
                || dl.getQtyDeliveredBase().compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return dl.getIssueValueAmount()
                .multiply(qtyReturnedBase)
                .divide(dl.getQtyDeliveredBase(), 4, RoundingMode.HALF_UP);
    }

    /** Net and VAT of one return line, in the order currency. */
    record ReturnAmounts(BigDecimal net, BigDecimal vat) {}

    /**
     * Net and VAT for returning {@code qtyReturnedBase} of an order line.
     *
     * <p>The order line already carries the net and VAT the customer was charged, computed by
     * {@link SalesOrderTotalsCalculator} with every discount (line and apportioned document
     * discount) and the VAT-inclusive stance applied. A return credits back the same share of
     * those: {@code net = lineNet × returned / ordered}, {@code vat = lineVat × returned / ordered},
     * both in BASE units on both sides and rounded HALF_UP to {@code scale} (the order currency's
     * minor units). This replaces {@code net × vatRate / 100}, which treated the stored FRACTION
     * (0.18) as a percentage and credited about one hundredth of the VAT.
     *
     * <p>A line that was never totalled (net and VAT both zero on a priced line) falls back to
     * {@code unitPrice × qty − discount} with VAT at the line's fractional rate on top.
     *
     * <p>Package-private and static so the arithmetic is testable without a database.
     */
    static ReturnAmounts proRateAmounts(SalesOrderLine sol, BigDecimal qtyReturnedBase, int scale) {
        BigDecimal orderedBase = sol.getQtyOrderedBase();
        BigDecimal solNet = sol.getNetAmount();
        BigDecimal solVat = sol.getVatAmount();
        boolean totalled = orderedBase != null && orderedBase.signum() > 0
                && solNet != null && solVat != null && solNet.add(solVat).signum() > 0;
        if (totalled) {
            return new ReturnAmounts(
                    solNet.multiply(qtyReturnedBase).divide(orderedBase, scale, RoundingMode.HALF_UP),
                    solVat.multiply(qtyReturnedBase).divide(orderedBase, scale, RoundingMode.HALF_UP));
        }
        BigDecimal net = computeLineNet(sol, qtyReturnedBase).setScale(scale, RoundingMode.HALF_UP);
        BigDecimal rate = sol.getVatRate() != null ? sol.getVatRate() : BigDecimal.ZERO;
        return new ReturnAmounts(net, net.multiply(rate).setScale(scale, RoundingMode.HALF_UP));
    }

    /**
     * Fallback line net for an order line that carries no computed totals:
     * {@code net = unitPrice × qtyReturned − proportional lineDiscount}, HALF_UP.
     */
    private static BigDecimal computeLineNet(SalesOrderLine sol, BigDecimal qtyReturnedBase) {
        BigDecimal unitPrice = sol.getUnitPriceAmount() != null
                ? sol.getUnitPriceAmount() : BigDecimal.ZERO;
        BigDecimal gross = unitPrice.multiply(qtyReturnedBase).setScale(4, RoundingMode.HALF_UP);
        BigDecimal discount = proRateDiscountAmount(sol, qtyReturnedBase);
        if (discount == null) discount = BigDecimal.ZERO;
        BigDecimal net = gross.subtract(discount);
        return net.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : net;
    }

    /**
     * Pro-rates the line discount amount for a partial return.
     * If the SOL has a fixed discount amount, pro-rate it proportionally.
     * If it has a percent, compute it against the gross.
     */
    private static BigDecimal proRateDiscountAmount(SalesOrderLine sol, BigDecimal qtyReturnedBase) {
        BigDecimal discAmt = sol.getLineDiscountAmount();
        BigDecimal discPct = sol.getLineDiscountPercent();

        if (discAmt != null && discAmt.compareTo(BigDecimal.ZERO) > 0) {
            // pro-rate based on qty ratio against the full ordered qty
            BigDecimal orderedBase = sol.getQtyOrderedBase();
            if (orderedBase != null && orderedBase.compareTo(BigDecimal.ZERO) > 0) {
                return discAmt.multiply(qtyReturnedBase)
                        .divide(orderedBase, 4, RoundingMode.HALF_UP);
            }
            return discAmt;
        } else if (discPct != null && discPct.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal unitPrice = sol.getUnitPriceAmount() != null
                    ? sol.getUnitPriceAmount() : BigDecimal.ZERO;
            return unitPrice.multiply(qtyReturnedBase)
                    .multiply(discPct)
                    .divide(new BigDecimal("100"), 4, RoundingMode.HALF_UP);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SalesReturn requireReturn(String uid) {
        return Lookups.orNotFound(returns.findByUid(uid), "SalesReturn", uid);
    }

    private Delivery requireDelivery(String uid) {
        return Lookups.orNotFound(deliveries.findByUid(uid), "Delivery", uid);
    }

    private SalesReturnDto toDto(SalesReturn r) {
        List<SalesReturnLine> lines = returnLines.findBySalesReturnIdOrderByLineNo(r.getId());
        return SalesReturnDto.from(r, lines.stream().map(SalesReturnLineDto::from).toList());
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
