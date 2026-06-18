package com.erp.modules.sales.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.sales.domain.dto.AddSalesOrderLineRequest;
import com.erp.modules.sales.domain.dto.CreateSalesOrderRequest;
import com.erp.modules.sales.domain.dto.CreateStandingOrderRequest;
import com.erp.modules.sales.domain.dto.StandingOrderDto;
import com.erp.modules.sales.domain.dto.StandingOrderGeneratedPayload;
import com.erp.modules.sales.domain.dto.StandingOrderLineDto;
import com.erp.modules.sales.domain.entity.StandingOrder;
import com.erp.modules.sales.domain.entity.StandingOrderLine;
import com.erp.modules.sales.domain.enums.StandingFrequency;
import com.erp.modules.sales.domain.enums.StandingStatus;
import com.erp.modules.sales.repository.SalesOrderRepository;
import com.erp.modules.sales.repository.StandingOrderLineRepository;
import com.erp.modules.sales.repository.StandingOrderRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class StandingOrderServiceImpl implements StandingOrderService {

    private static final Logger log = LoggerFactory.getLogger(StandingOrderServiceImpl.class);

    private final StandingOrderRepository     standings;
    private final StandingOrderLineRepository standingLines;
    private final SalesOrderRepository        salesOrders;
    private final SalesOrderService           salesOrderService;
    private final SalesDepthNumberGenerator   numberGen;
    private final CompanyRepository           companies;
    private final CustomerRepository          customers;
    private final ProductRepository           products;
    private final UnitOfMeasureRepository     units;
    private final OutboxPublisher             outbox;
    private final ScopeGuard                  scopeGuard;
    private final AuditService                audit;

    public StandingOrderServiceImpl(StandingOrderRepository standings,
                                     StandingOrderLineRepository standingLines,
                                     SalesOrderRepository salesOrders,
                                     SalesOrderService salesOrderService,
                                     SalesDepthNumberGenerator numberGen,
                                     CompanyRepository companies,
                                     CustomerRepository customers,
                                     ProductRepository products,
                                     UnitOfMeasureRepository units,
                                     OutboxPublisher outbox,
                                     ScopeGuard scopeGuard,
                                     AuditService audit) {
        this.standings         = standings;
        this.standingLines     = standingLines;
        this.salesOrders       = salesOrders;
        this.salesOrderService = salesOrderService;
        this.numberGen         = numberGen;
        this.companies         = companies;
        this.customers         = customers;
        this.products          = products;
        this.units             = units;
        this.outbox            = outbox;
        this.scopeGuard        = scopeGuard;
        this.audit             = audit;
    }

    @Override
    public StandingOrderDto createStanding(CreateStandingOrderRequest req) {
        var company = companies.findByUid(req.companyUid())
                .orElseThrow(() -> NotFoundException.of("Company", req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), company.getId());
        var customer = customers.findById(req.customerId())
                .orElseThrow(() -> NotFoundException.of("Customer", String.valueOf(req.customerId())));

        var standing = new StandingOrder(company.getId(), req.branchId(), customer.getId(),
                req.currency(), req.frequency(), req.startDate(), req.endDate(), actorId());
        standing.setOrderNumber(numberGen.nextStanding(company.getId()));
        standing.setNotes(req.notes());
        standing = standings.save(standing);

        // Create lines
        short lineNo = 1;
        List<StandingOrderLine> savedLines = new ArrayList<>();
        for (var lineReq : req.lines()) {
            var product = products.findById(lineReq.productId())
                    .orElseThrow(() -> NotFoundException.of("Product", String.valueOf(lineReq.productId())));
            var unit = units.findById(lineReq.unitId())
                    .orElseThrow(() -> NotFoundException.of("Unit", String.valueOf(lineReq.unitId())));
            var line = new StandingOrderLine(standing.getId(), company.getId(), req.branchId(),
                    lineNo++, product.getId(), product.getCode(), product.getName(),
                    unit.getId(), unit.getName(),
                    lineReq.qty(), lineReq.qtyBase(),
                    lineReq.unitPriceAmount(), req.currency(), actorId());
            savedLines.add(standingLines.save(line));
        }

        audit.record(AuditEvent.of(AuditActions.STANDING_ORDER_CREATE, "standing_orders",
                standing.getId(), standing.getUid())
                .detail(Map.of("orderNumber", standing.getOrderNumber())));
        return toDto(standing, savedLines);
    }

    @Override
    @Transactional(readOnly = true)
    public StandingOrderDto getStandingByUid(String uid) {
        var standing = requireStanding(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), standing.getCompanyId());
        return toDto(standing, standingLines.findByStandingOrderId(standing.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StandingOrderDto> listStandings(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return standings.findByCompanyId(companyId, pageable)
                .map(s -> toDto(s, standingLines.findByStandingOrderId(s.getId())));
    }

    @Override
    public void pauseStanding(String uid) {
        var standing = requireStanding(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), standing.getCompanyId());
        if (standing.getStatus() != StandingStatus.ACTIVE) {
            throw new ConflictException("Standing order " + uid + " is not ACTIVE.");
        }
        standing.setStatus(StandingStatus.PAUSED);
        standing.setUpdatedAt(Instant.now());
        standing.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.STANDING_ORDER_PAUSE, "standing_orders",
                standing.getId(), standing.getUid()));
    }

    @Override
    public void resumeStanding(String uid) {
        var standing = requireStanding(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), standing.getCompanyId());
        if (standing.getStatus() != StandingStatus.PAUSED) {
            throw new ConflictException("Standing order " + uid + " is not PAUSED.");
        }
        standing.setStatus(StandingStatus.ACTIVE);
        standing.setUpdatedAt(Instant.now());
        standing.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.STANDING_ORDER_RESUME, "standing_orders",
                standing.getId(), standing.getUid()));
    }

    @Override
    public void cancelStanding(String uid) {
        var standing = requireStanding(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), standing.getCompanyId());
        if (standing.getStatus() == StandingStatus.CANCELLED) {
            throw new ConflictException("Standing order " + uid + " is already CANCELLED.");
        }
        standing.setStatus(StandingStatus.CANCELLED);
        standing.setUpdatedAt(Instant.now());
        standing.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.STANDING_ORDER_CANCEL, "standing_orders",
                standing.getId(), standing.getUid()));
    }

    @Override
    public StandingOrderDto triggerNow(String uid) {
        var standing = requireStanding(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), standing.getCompanyId());
        if (standing.getStatus() != StandingStatus.ACTIVE) {
            throw new ConflictException("Standing order " + uid + " is not ACTIVE.");
        }
        generateSo(standing, LocalDate.now());
        return toDto(standing, standingLines.findByStandingOrderId(standing.getId()));
    }

    @Override
    @Scheduled(cron = "0 0 0 * * *")  // midnight daily
    public void generateDue() {
        LocalDate today = LocalDate.now();
        var due = standings.findDueForGeneration(today);
        log.info("StandingOrder scheduler: {} due order(s) for {}", due.size(), today);
        for (var standing : due) {
            try {
                generateSo(standing, today);
            } catch (Exception ex) {
                log.error("Standing order generation failed for uid={}: {}", standing.getUid(), ex.getMessage(), ex);
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------

    private void generateSo(StandingOrder standing, LocalDate runDate) {
        var company = companies.findById(standing.getCompanyId())
                .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(standing.getCompanyId())));
        var customer = customers.findById(standing.getCustomerId())
                .orElseThrow(() -> NotFoundException.of("Customer", String.valueOf(standing.getCustomerId())));

        var soDto = salesOrderService.create(new CreateSalesOrderRequest(
                company.getUid(), customer.getUid(), null,
                standing.getCurrency().value(), runDate, null, null, null, null));

        // Stamp source_standing_uid
        var soEntity = salesOrders.findByUid(soDto.uid())
                .orElseThrow(() -> NotFoundException.of("SalesOrder", soDto.uid()));
        soEntity.setSourceStandingUid(standing.getUid());
        soEntity.setUpdatedAt(Instant.now());

        // Add lines from template
        var lines = standingLines.findByStandingOrderId(standing.getId());
        for (var tLine : lines) {
            var product = products.findById(tLine.getProductId())
                    .orElseThrow(() -> NotFoundException.of("Product", String.valueOf(tLine.getProductId())));
            var unit = units.findById(tLine.getUnitId())
                    .orElseThrow(() -> NotFoundException.of("Unit", String.valueOf(tLine.getUnitId())));
            salesOrderService.addLine(soDto.uid(), new AddSalesOrderLineRequest(
                    product.getUid(), unit.getUid(), tLine.getQtyBase(),
                    tLine.getUnitPriceAmount(), null, null));
        }

        // Advance next_run_date
        standing.setNextRunDate(advanceDate(standing.getNextRunDate(), standing.getFrequency()));
        standing.setUpdatedAt(Instant.now());

        // Publish outbox event
        outbox.publish(DomainEventType.STANDING_ORDER_GENERATED, DomainEventType.AGG_STANDING_ORDER,
                standing.getId(), standing.getUid(),
                standing.getCompanyId(), standing.getBranchId(),
                new StandingOrderGeneratedPayload(standing.getUid(), soDto.uid(),
                        standing.getCompanyId(), standing.getBranchId()));

        audit.record(AuditEvent.of(AuditActions.STANDING_ORDER_GENERATED, "standing_orders",
                standing.getId(), standing.getUid())
                .detail(Map.of("generatedSoUid", soDto.uid())));
    }

    private LocalDate advanceDate(LocalDate current, StandingFrequency frequency) {
        return switch (frequency) {
            case DAILY     -> current.plusDays(1);
            case WEEKLY    -> current.plusWeeks(1);
            case BIWEEKLY  -> current.plusWeeks(2);
            case MONTHLY   -> current.plusMonths(1);
        };
    }

    private StandingOrder requireStanding(String uid) {
        return standings.findByUid(uid).orElseThrow(() -> NotFoundException.of("StandingOrder", uid));
    }

    private Long actorId() {
        var p = RequestContext.get();
        return (p != null) ? p.userId() : null;
    }

    private StandingOrderDto toDto(StandingOrder s, List<StandingOrderLine> lines) {
        return new StandingOrderDto(s.getId(), s.getUid(), s.getCompanyId(), s.getBranchId(),
                s.getOrderNumber(), s.getCustomerId(), s.getCurrency().value(),
                s.getFrequency(), s.getStartDate() == null ? null : s.getStartDate().toString(),
                s.getEndDate() == null ? null : s.getEndDate().toString(),
                s.getNextRunDate() == null ? null : s.getNextRunDate().toString(),
                s.getLastRunDate() == null ? null : s.getLastRunDate().toString(),
                s.getOccurrencesGenerated(), s.getMaxOccurrences(), s.isAutoConfirm(),
                s.getStatus(), s.getNotes(),
                lines.stream().map(this::toLineDto).toList());
    }

    private StandingOrderLineDto toLineDto(StandingOrderLine l) {
        return new StandingOrderLineDto(l.getId(), l.getUid(), l.getStandingOrderId(),
                l.getLineNo(), l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(), l.getQty(), l.getQtyBase(),
                l.getUnitPriceAmount(), l.getCurrency().value());
    }
}
