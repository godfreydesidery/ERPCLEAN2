package com.erp.modules.sales.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.sales.domain.dto.AddSalesOrderLineRequest;
import com.erp.modules.sales.domain.dto.BlanketOrderDto;
import com.erp.modules.sales.domain.dto.BlanketOrderLineDto;
import com.erp.modules.sales.domain.dto.CreateBlanketOrderRequest;
import com.erp.modules.sales.domain.dto.CreateSalesOrderRequest;
import com.erp.modules.sales.domain.dto.DrawBlanketRequest;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.domain.entity.BlanketOrder;
import com.erp.modules.sales.domain.entity.BlanketOrderLine;
import com.erp.modules.sales.domain.entity.SalesOrder;
import com.erp.modules.sales.domain.enums.BlanketStatus;
import com.erp.modules.sales.repository.BlanketOrderLineRepository;
import com.erp.modules.sales.repository.BlanketOrderRepository;
import com.erp.modules.sales.repository.SalesOrderRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BlanketOrderServiceImpl implements BlanketOrderService {

    private final BlanketOrderRepository     blankets;
    private final BlanketOrderLineRepository blanketLines;
    private final SalesOrderRepository       salesOrders;
    private final SalesOrderService          salesOrderService;
    private final SalesDepthNumberGenerator  numberGen;
    private final CompanyRepository          companies;
    private final CustomerRepository         customers;
    private final ProductRepository          products;
    private final UnitOfMeasureRepository    units;
    private final ScopeGuard                 scopeGuard;
    private final AuditService               audit;

    public BlanketOrderServiceImpl(BlanketOrderRepository blankets,
                                    BlanketOrderLineRepository blanketLines,
                                    SalesOrderRepository salesOrders,
                                    SalesOrderService salesOrderService,
                                    SalesDepthNumberGenerator numberGen,
                                    CompanyRepository companies,
                                    CustomerRepository customers,
                                    ProductRepository products,
                                    UnitOfMeasureRepository units,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.blankets          = blankets;
        this.blanketLines      = blanketLines;
        this.salesOrders       = salesOrders;
        this.salesOrderService = salesOrderService;
        this.numberGen         = numberGen;
        this.companies         = companies;
        this.customers         = customers;
        this.products          = products;
        this.units             = units;
        this.scopeGuard        = scopeGuard;
        this.audit             = audit;
    }

    @Override
    public BlanketOrderDto createBlanket(CreateBlanketOrderRequest req) {
        var company = companies.findByUid(req.companyUid())
                .orElseThrow(() -> NotFoundException.of("Company", req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), company.getId());
        var customer = customers.findById(req.customerId())
                .orElseThrow(() -> NotFoundException.of("Customer", String.valueOf(req.customerId())));

        if (req.validTo().isBefore(req.validFrom())) {
            throw new IllegalArgumentException("validTo must not be before validFrom.");
        }

        var blanket = new BlanketOrder(company.getId(), req.branchId(), customer.getId(),
                req.currency(), req.validFrom(), req.validTo(), actorId());
        blanket.setOrderNumber(numberGen.nextBlanket(company.getId()));
        blanket.setNotes(req.notes());
        blanket = blankets.save(blanket);

        // Create lines
        short lineNo = 1;
        List<BlanketOrderLine> savedLines = new ArrayList<>();
        BigDecimal totalCommitted = BigDecimal.ZERO;
        for (var lineReq : req.lines()) {
            var product = products.findById(lineReq.productId())
                    .orElseThrow(() -> NotFoundException.of("Product", String.valueOf(lineReq.productId())));
            var unit = units.findById(lineReq.unitId())
                    .orElseThrow(() -> NotFoundException.of("Unit", String.valueOf(lineReq.unitId())));
            var line = new BlanketOrderLine(blanket.getId(), blanket.getCompanyId(), blanket.getBranchId(),
                    lineNo++, product.getId(), product.getCode(), product.getName(),
                    unit.getId(), unit.getName(),
                    lineReq.committedQtyBase(), lineReq.unitPriceAmount(),
                    req.currency(), actorId());
            savedLines.add(blanketLines.save(line));
            totalCommitted = totalCommitted.add(lineReq.committedQtyBase().multiply(lineReq.unitPriceAmount()));
        }
        blanket.setTotalCommittedAmount(totalCommitted);

        audit.record(AuditEvent.of(AuditActions.BLANKET_ORDER_CREATE, "blanket_orders",
                blanket.getId(), blanket.getUid())
                .detail(Map.of("orderNumber", blanket.getOrderNumber())));
        return toDto(blanket, savedLines);
    }

    @Override
    @Transactional(readOnly = true)
    public BlanketOrderDto getBlanketByUid(String uid) {
        var blanket = requireBlanket(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), blanket.getCompanyId());
        var lines = blanketLines.findByBlanketOrderId(blanket.getId());
        return toDto(blanket, lines);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BlanketOrderDto> listBlankets(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return blankets.findByCompanyId(companyId, pageable)
                .map(b -> toDto(b, blanketLines.findByBlanketOrderId(b.getId())));
    }

    @Override
    public void cancelBlanket(String uid) {
        var blanket = requireBlanket(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), blanket.getCompanyId());
        if (blanket.getStatus() == BlanketStatus.CANCELLED) {
            throw new ConflictException("This blanket order is already CANCELLED.");
        }
        blanket.setStatus(BlanketStatus.CANCELLED);
        blanket.setUpdatedAt(Instant.now());
        blanket.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.BLANKET_ORDER_CANCEL, "blanket_orders",
                blanket.getId(), blanket.getUid()));
    }

    @Override
    public SalesOrderDto drawAgainstBlanket(DrawBlanketRequest req) {
        var blanket = requireBlanket(req.blanketUid());
        scopeGuard.assertCanActIn(RequestContext.get(), blanket.getCompanyId());

        if (blanket.getStatus() != BlanketStatus.ACTIVE) {
            throw new ConflictException("This blanket order is not ACTIVE.");
        }
        if (LocalDate.now().isAfter(blanket.getValidTo())) {
            throw new ConflictException("This blanket order has expired.");
        }

        var company = companies.findById(blanket.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(blanket.getCompanyId())));
        var customer = customers.findById(blanket.getCustomerId())
                .orElseThrow(() -> NotFoundException.of("Customer", String.valueOf(blanket.getCustomerId())));

        // Validate draw quantities
        for (var drawLine : req.lines()) {
            var bLine = blanketLines.findByUid(drawLine.blanketLineUid())
                    .orElseThrow(() -> NotFoundException.of("BlanketOrderLine", drawLine.blanketLineUid()));
            BigDecimal remaining = bLine.remainingQtyBase();
            if (drawLine.qtyBase().compareTo(remaining) > 0) {
                throw new ConflictException("Draw quantity " + drawLine.qtyBase()
                        + " exceeds remaining " + remaining
                        + " on blanket line " + drawLine.blanketLineUid());
            }
        }

        // Create the SO via SalesOrderService
        var soDto = salesOrderService.create(new CreateSalesOrderRequest(
                company.getUid(), customer.getUid(), null,
                blanket.getCurrency().value(), LocalDate.now(), null, null, null, null));

        // Stamp source_blanket_uid on the SO entity
        var soEntity = salesOrders.findByUid(soDto.uid())
                .orElseThrow(() -> NotFoundException.of("SalesOrder", soDto.uid()));
        soEntity.setSourceBlanketUid(blanket.getUid());
        soEntity.setUpdatedAt(Instant.now());
        soEntity.setUpdatedBy(actorId());

        // Add lines and decrement drawn qty
        BigDecimal totalDrawn = BigDecimal.ZERO;
        for (var drawLine : req.lines()) {
            var bLine = blanketLines.findByUid(drawLine.blanketLineUid())
                    .orElseThrow(() -> NotFoundException.of("BlanketOrderLine", drawLine.blanketLineUid()));

            var product = products.findById(bLine.getProductId())
                    .orElseThrow(() -> NotFoundException.of("Product", String.valueOf(bLine.getProductId())));
            var unit = units.findById(bLine.getUnitId())
                    .orElseThrow(() -> NotFoundException.of("Unit", String.valueOf(bLine.getUnitId())));

            salesOrderService.addLine(soDto.uid(), new AddSalesOrderLineRequest(
                    product.getUid(), unit.getUid(), drawLine.qtyBase(),
                    bLine.getUnitPriceAmount(), null, null));

            // Decrement drawn qty — DB CHECK enforces drawn <= committed
            bLine.setDrawnQtyBase(bLine.getDrawnQtyBase().add(drawLine.qtyBase()));
            bLine.setUpdatedAt(Instant.now());
            bLine.setUpdatedBy(actorId());
            totalDrawn = totalDrawn.add(drawLine.qtyBase().multiply(bLine.getUnitPriceAmount()));
        }

        blanket.setTotalDrawnAmount(blanket.getTotalDrawnAmount().add(totalDrawn));
        blanket.setUpdatedAt(Instant.now());
        blanket.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.BLANKET_ORDER_DRAW, "blanket_orders",
                blanket.getId(), blanket.getUid())
                .detail(Map.of("salesOrderUid", soDto.uid())));

        return salesOrderService.getByUid(soDto.uid());
    }

    private BlanketOrder requireBlanket(String uid) {
        return blankets.findByUid(uid).orElseThrow(() -> NotFoundException.of("BlanketOrder", uid));
    }

    private Long actorId() {
        var p = RequestContext.get();
        return (p != null) ? p.userId() : null;
    }

    private BlanketOrderDto toDto(BlanketOrder b, List<BlanketOrderLine> lines) {
        return new BlanketOrderDto(b.getId(), b.getUid(), b.getCompanyId(), b.getBranchId(),
                b.getOrderNumber(), b.getCustomerId(), b.getCurrency().value(),
                b.getValidFrom() == null ? null : b.getValidFrom().toString(),
                b.getValidTo() == null ? null : b.getValidTo().toString(),
                b.getTotalCommittedAmount(), b.getTotalDrawnAmount(),
                b.getStatus(), b.getNotes(),
                lines.stream().map(this::toLineDto).toList());
    }

    private BlanketOrderLineDto toLineDto(BlanketOrderLine l) {
        return new BlanketOrderLineDto(l.getId(), l.getUid(), l.getBlanketOrderId(),
                l.getLineNo(), l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(),
                l.getCommittedQtyBase(), l.getDrawnQtyBase(), l.remainingQtyBase(),
                l.getUnitPriceAmount(), l.getCurrency().value());
    }
}
