package com.erp.modules.stock.service;

import com.erp.modules.parties.domain.dto.AgentDto;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.stock.domain.dto.CreateVanReconciliationRequest;
import com.erp.modules.stock.domain.dto.EnterVanReconciliationLinesRequest;
import com.erp.modules.stock.domain.dto.VanMovementSumRowDto;
import com.erp.modules.stock.domain.dto.VanReconciledPayload;
import com.erp.modules.stock.domain.dto.VanReconciliationDto;
import com.erp.modules.stock.domain.dto.VanReconciliationLineDto;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.entity.VanReconciliation;
import com.erp.modules.stock.domain.entity.VanReconciliationLine;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.domain.enums.VanReconciliationStatus;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.modules.stock.repository.VanReconciliationLineRepository;
import com.erp.modules.stock.repository.VanReconciliationRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Van-stock reconciliation service (ADR-0051 D-8).
 *
 * <p><strong>RECORD-ONLY (D-8.2):</strong> no method here ever calls {@link StockPostingService} or
 * a GL posting service. The van's on-hand is not truthful (route sales issue from the branch
 * warehouse, never the van), so this worksheet is an accountability control, not a stock/valuation
 * correction — {@link #reconcile} only flips status and emits an informational outbox event.
 */
@Service
@Transactional
public class VanReconciliationServiceImpl implements VanReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(VanReconciliationServiceImpl.class);

    private final VanReconciliationRepository     reconciliations;
    private final VanReconciliationLineRepository lineRepo;
    private final StockMovementRepository         movements;
    private final StockOnHandRepository           onHands;
    private final StockLocationRepository         stockLocations;
    private final LocationResolver                locationResolver;
    private final ProductService                  productService;
    private final AgentService                    agentService;
    private final WarehouseNumberGenerator        numberGenerator;
    private final OutboxPublisher                 outbox;
    private final ScopeGuard                      scopeGuard;
    private final AuditService                    audit;

    public VanReconciliationServiceImpl(VanReconciliationRepository reconciliations,
                                        VanReconciliationLineRepository lineRepo,
                                        StockMovementRepository movements,
                                        StockOnHandRepository onHands,
                                        StockLocationRepository stockLocations,
                                        LocationResolver locationResolver,
                                        ProductService productService,
                                        AgentService agentService,
                                        WarehouseNumberGenerator numberGenerator,
                                        OutboxPublisher outbox,
                                        ScopeGuard scopeGuard,
                                        AuditService audit) {
        this.reconciliations = reconciliations;
        this.lineRepo         = lineRepo;
        this.movements        = movements;
        this.onHands          = onHands;
        this.stockLocations   = stockLocations;
        this.locationResolver = locationResolver;
        this.productService   = productService;
        this.agentService     = agentService;
        this.numberGenerator  = numberGenerator;
        this.outbox           = outbox;
        this.scopeGuard       = scopeGuard;
        this.audit            = audit;
    }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    @Override
    public VanReconciliationDto create(CreateVanReconciliationRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal.companyId());

        StockLocation van = locationResolver.resolveLocation(
                request.vanLocationUid(), principal.companyId());
        if (van.getLocationType() != LocationType.VAN) {
            throw new IllegalArgumentException(
                    "The selected location is not a van; van reconciliations are only for VAN locations.");
        }

        if (reconciliations.existsByVanLocationIdAndBusinessDateAndStatusNot(
                van.getId(), request.businessDate(), VanReconciliationStatus.CANCELLED)) {
            throw new ConflictException(
                    "A van reconciliation for this van on this business date already exists.");
        }

        String number = numberGenerator.nextVanRecon(principal.companyId());
        VanReconciliation recon = new VanReconciliation(
                principal.companyId(), van.getBranchId(), number, van.getId(),
                van.getAgentId(), request.businessDate(), request.notes(), principal.userId());
        reconciliations.save(recon);

        buildLines(recon, van, request);

        audit.record(AuditEvent.of(AuditActions.VAN_RECON_CREATE, "van_reconciliations",
                        recon.getId(), recon.getUid())
                .detail(Map.of("reconNumber", number,
                        "businessDate", String.valueOf(request.businessDate()))));

        return toDto(recon, lineRepo.findByVanReconciliationIdOrderByLineNoAsc(recon.getId()));
    }

    /** Derives loaded/returned per product from the transfer ledger and writes the worksheet lines. */
    private void buildLines(VanReconciliation recon, StockLocation van,
                            CreateVanReconciliationRequest request) {
        Instant windowStart = request.businessDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant windowEnd   = request.businessDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Map<Long, BigDecimal> loadedByProduct = sumToMap(movements.sumByLocationTypeAndWindow(
                recon.getCompanyId(), van.getBranchId(), van.getId(),
                MovementType.TRANSFER_IN, windowStart, windowEnd));
        // TRANSFER_OUT rows are signed negative; returned = -Σ(TRANSFER_OUT).
        Map<Long, BigDecimal> returnedRawByProduct = sumToMap(movements.sumByLocationTypeAndWindow(
                recon.getCompanyId(), van.getBranchId(), van.getId(),
                MovementType.TRANSFER_OUT, windowStart, windowEnd));

        Set<Long> productIds = resolveInScopeProductIds(
                request.productUids(), loadedByProduct.keySet(), returnedRawByProduct.keySet());

        short lineNo = 0;
        for (Long productId : productIds) {
            lineNo++;
            BigDecimal loaded   = loadedByProduct.getOrDefault(productId, BigDecimal.ZERO);
            BigDecimal returned = returnedRawByProduct.getOrDefault(productId, BigDecimal.ZERO).negate();

            String code = String.valueOf(productId);
            String name = code;
            try {
                ProductDto p = productService.getById(productId);
                if (p != null) {
                    code = p.code();
                    name = p.name();
                }
            } catch (Exception ignored) {
                // product not found: keep numeric defaults (mirrors StockCountServiceImpl)
            }

            BigDecimal unitCost = onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                            recon.getCompanyId(), van.getBranchId(), van.getId(), productId)
                    .map(StockOnHand::getAvgCost)
                    .orElse(null);

            VanReconciliationLine line = new VanReconciliationLine(
                    recon.getId(), lineNo, productId, code, name, null);
            line.applyDerived(loaded, returned, unitCost);
            lineRepo.save(line);
        }
    }

    private Set<Long> resolveInScopeProductIds(List<String> productUids,
                                               Set<Long> loadedIds, Set<Long> returnedIds) {
        Set<Long> productIds = new TreeSet<>();
        if (productUids != null && !productUids.isEmpty()) {
            for (String pUid : productUids) {
                try {
                    productIds.add(productService.getByUid(pUid).id());
                } catch (Exception ex) {
                    log.warn("VanReconciliation: product uid {} not found — skipping from worksheet", pUid);
                }
            }
            return productIds;
        }
        productIds.addAll(loadedIds);
        productIds.addAll(returnedIds);
        return productIds;
    }

    private static Map<Long, BigDecimal> sumToMap(List<VanMovementSumRowDto> rows) {
        Map<Long, BigDecimal> map = new HashMap<>();
        for (VanMovementSumRowDto row : rows) {
            map.put(row.productId(), row.qty());
        }
        return map;
    }

    // -------------------------------------------------------------------------
    // enterLines
    // -------------------------------------------------------------------------

    @Override
    public VanReconciliationDto enterLines(String uid, EnterVanReconciliationLinesRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        VanReconciliation recon = findAndAssertScope(uid, principal);

        if (recon.getStatus() != VanReconciliationStatus.OPEN) {
            // uid intentionally not surfaced (error-hygiene rule)
            throw new IllegalStateException(
                    "This van reconciliation is not OPEN (current status: " + recon.getStatus() + ").");
        }

        // Version-touch the header (backend review fix): enterLines only used to save the
        // (un-versioned) line rows, so a concurrent reconcile()/cancel() on the same header could
        // flip status to RECONCILED/CANCELLED and both commit — mutating a frozen worksheet. Saving
        // the header here makes it participate in optimistic locking (@Version via UidEntity): a
        // racing reconcile()/cancel() now surfaces as ObjectOptimisticLockingFailureException on
        // whichever of the two commits second, instead of a silent lost freeze.
        recon.touch(principal.userId());
        reconciliations.save(recon);

        List<VanReconciliationLine> allLines =
                lineRepo.findByVanReconciliationIdOrderByLineNoAsc(recon.getId());

        for (EnterVanReconciliationLinesRequest.LineEntry entry : request.lines()) {
            VanReconciliationLine line = resolveLine(entry, allLines, recon.getId());

            if (entry.loadedQty() != null || entry.returnedQty() != null) {
                BigDecimal newLoaded   = entry.loadedQty() != null ? entry.loadedQty() : line.getLoadedQty();
                BigDecimal newReturned = entry.returnedQty() != null ? entry.returnedQty() : line.getReturnedQty();
                line.applyDerived(newLoaded, newReturned, line.getUnitCostAmount());
            }
            line.enterCounts(entry.soldQty(), entry.physicalQty(), principal.userId());
            lineRepo.save(line);
        }

        audit.record(AuditEvent.of(AuditActions.VAN_RECON_ENTER, "van_reconciliations",
                        recon.getId(), recon.getUid())
                .detail(Map.of("linesEntered", String.valueOf(request.lines().size()))));

        return toDto(recon, lineRepo.findByVanReconciliationIdOrderByLineNoAsc(recon.getId()));
    }

    private VanReconciliationLine resolveLine(EnterVanReconciliationLinesRequest.LineEntry entry,
                                              List<VanReconciliationLine> allLines, Long reconId) {
        if (entry.lineId() != null) {
            VanReconciliationLine line = allLines.stream()
                    .filter(l -> l.getId().equals(entry.lineId()))
                    .findFirst()
                    .orElseThrow(() -> NotFoundException.of(
                            "VanReconciliationLine", String.valueOf(entry.lineId())));
            if (!line.getVanReconciliationId().equals(reconId)) {
                // reconId / line id intentionally not surfaced (error-hygiene rule)
                throw new IllegalArgumentException(
                        "One or more lines do not belong to the specified van reconciliation.");
            }
            return line;
        }
        if (entry.productUid() != null && !entry.productUid().isBlank()) {
            Long productId = productService.getByUid(entry.productUid()).id();
            return allLines.stream()
                    .filter(l -> l.getProductId().equals(productId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "One or more products are not part of this van reconciliation."));
        }
        throw new IllegalArgumentException(
                "Each line entry must identify its line (lineId or productUid).");
    }

    // -------------------------------------------------------------------------
    // reconcile
    // -------------------------------------------------------------------------

    @Override
    public VanReconciliationDto reconcile(String uid) {
        RequestContext.Principal principal = RequestContext.get();
        VanReconciliation recon = findAndAssertScope(uid, principal);

        if (recon.getStatus() != VanReconciliationStatus.OPEN) {
            throw new IllegalStateException(
                    "This van reconciliation must be OPEN before it can be reconciled (current status: "
                            + recon.getStatus() + ").");
        }

        // RECORD-ONLY (ADR-0051 D-8.2): status flip + audit + informational outbox event only.
        // No StockPostingService / GL posting call — the van on-hand is not truthful.
        recon.reconcile(principal.userId());
        reconciliations.save(recon);

        outbox.publish(DomainEventType.VAN_RECONCILED,
                DomainEventType.AGG_VAN_RECONCILIATION,
                recon.getId(), recon.getUid(),
                recon.getCompanyId(), recon.getBranchId(),
                new VanReconciledPayload(recon.getUid(), recon.getCompanyId(), recon.getBranchId(),
                        recon.getVanLocationId(), recon.getAgentId(), recon.getBusinessDate(),
                        recon.getReconciledAt()));

        audit.record(AuditEvent.of(AuditActions.VAN_RECON_RECONCILE, "van_reconciliations",
                recon.getId(), recon.getUid()).detail(Map.of()));

        return toDto(recon, lineRepo.findByVanReconciliationIdOrderByLineNoAsc(recon.getId()));
    }

    // -------------------------------------------------------------------------
    // cancel
    // -------------------------------------------------------------------------

    @Override
    public VanReconciliationDto cancel(String uid) {
        RequestContext.Principal principal = RequestContext.get();
        VanReconciliation recon = findAndAssertScope(uid, principal);

        if (recon.getStatus() != VanReconciliationStatus.OPEN) {
            // uid intentionally not surfaced (error-hygiene rule)
            throw new IllegalStateException(
                    "This van reconciliation is not OPEN and cannot be cancelled (current status: "
                            + recon.getStatus() + ").");
        }
        recon.cancel(principal.userId());
        reconciliations.save(recon);

        audit.record(AuditEvent.of(AuditActions.VAN_RECON_CANCEL, "van_reconciliations",
                recon.getId(), recon.getUid()).detail(Map.of()));

        return toDto(recon, lineRepo.findByVanReconciliationIdOrderByLineNoAsc(recon.getId()));
    }

    // -------------------------------------------------------------------------
    // reads
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public VanReconciliationDto getByUid(String uid) {
        RequestContext.Principal principal = RequestContext.get();
        VanReconciliation recon = findAndAssertScope(uid, principal);
        return toDto(recon, lineRepo.findByVanReconciliationIdOrderByLineNoAsc(recon.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<VanReconciliationDto> list(Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal.companyId());
        return reconciliations.findByCompanyIdAndBranchId(
                        principal.companyId(), principal.branchId(), pageable)
                .map(r -> toDto(r, List.of()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private VanReconciliation findAndAssertScope(String uid, RequestContext.Principal principal) {
        VanReconciliation recon = reconciliations.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("VanReconciliation", uid));
        scopeGuard.assertCanActIn(principal, recon.getCompanyId());
        return recon;
    }

    private VanReconciliationDto toDto(VanReconciliation r, List<VanReconciliationLine> lines) {
        // Company-scoped (TenantScopingRulesTest): r.getCompanyId() was already established by
        // findAndAssertScope; the lookup below filters in the database, never loading a foreign
        // company's location row.
        StockLocation van = stockLocations
                .findByCompanyIdAndId(r.getCompanyId(), r.getVanLocationId())
                .orElse(null);
        String vanLocationUid  = van != null ? van.getUid() : null;
        String vanLocationName = van != null ? van.getName() : null;

        String agentUid  = null;
        String agentName = null;
        if (r.getAgentId() != null) {
            AgentDto agent = agentService.getByCompanyIdAndId(r.getCompanyId(), r.getAgentId());
            if (agent != null) {
                agentUid  = agent.uid();
                agentName = agent.displayName();
            }
        }

        List<VanReconciliationLineDto> lineDtos = lines.stream()
                .map(this::toLineDto)
                .toList();

        return new VanReconciliationDto(
                r.getId(), r.getUid(), r.getCompanyId(), r.getBranchId(),
                vanLocationUid, vanLocationName, agentUid, agentName,
                r.getReconNumber(), r.getBusinessDate(), r.getStatus(), r.getNotes(),
                lineDtos, r.getReconciledAt());
    }

    private VanReconciliationLineDto toLineDto(VanReconciliationLine l) {
        String productUid = null;
        try {
            ProductDto p = productService.getById(l.getProductId());
            if (p != null) {
                productUid = p.uid();
            }
        } catch (Exception ignored) {
            // product not found: leave productUid null (denormalised name/code still shown)
        }
        return new VanReconciliationLineDto(
                l.getId(), (int) l.getLineNo(), productUid, l.getProductName(),
                l.getUnitCostAmount(), l.getLoadedQty(), l.getSoldQty(), l.getReturnedQty(),
                l.getExpectedQty(), l.getPhysicalQty(), l.getVarianceQty(), l.getVarianceValue());
    }
}
