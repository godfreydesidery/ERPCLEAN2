package com.erp.modules.stock.service;

import com.erp.modules.products.service.ProductService;
import com.erp.modules.stock.domain.dto.CreateStockCountRequest;
import com.erp.modules.stock.domain.dto.EnterCountRequest;
import com.erp.modules.stock.domain.dto.StockCountDto;
import com.erp.modules.stock.domain.dto.StockCountLineDto;
import com.erp.modules.stock.domain.dto.StockCountPostedPayload;
import com.erp.modules.stock.domain.entity.StockCount;
import com.erp.modules.stock.domain.entity.StockCountLine;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.enums.AdjustmentReason;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.domain.enums.StockCountStatus;
import com.erp.modules.stock.repository.StockCountLineRepository;
import com.erp.modules.stock.repository.StockCountRepository;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * Stock count / cycle count service (ADR-0028 D-6).
 *
 * <p>Create: snapshot {@code system_qty} per in-scope product at the location,
 * write lines, and freeze to COUNTING.
 *
 * <p>Post: measure each line's variance against the system quantity AS AT THE MOMENT THE LINE
 * WAS COUNTED — live on-hand minus the net movements at the location since the count was entered
 * (STK-02); post ADJUSTMENT movement per non-zero-variance line; call one
 * {@link InventoryGlPoster#postAdjustmentDirect} with the net accumulated variance for the whole
 * count (D-6 decision).
 */
@Service
@Transactional
public class StockCountServiceImpl implements StockCountService {

    private static final Logger log = LoggerFactory.getLogger(StockCountServiceImpl.class);
    private static final int   SCALE = 4;
    private static final RoundingMode RM = RoundingMode.HALF_UP;
    private static final String BASE_CURRENCY = "TZS";

    private final StockCountRepository     counts;
    private final StockCountLineRepository countLines;
    private final StockOnHandRepository    onHands;
    private final StockMovementRepository  movements;
    private final StockPostingService      posting;
    private final InventoryValuationService valuation;
    private final InventoryGlPoster        glPoster;
    private final ProductService           productService;
    private final LocationResolver         locationResolver;
    private final WarehouseNumberGenerator numberGenerator;
    private final OutboxPublisher          outbox;
    private final ScopeGuard               scopeGuard;
    private final AuditService             audit;
    /** STK-08: counted quantities may be stated in a pack size. Stateless, so built here. */
    private final StockUnitResolver        unitResolver;

    public StockCountServiceImpl(StockCountRepository counts,
                                  StockCountLineRepository countLines,
                                  StockOnHandRepository onHands,
                                  StockMovementRepository movements,
                                  StockPostingService posting,
                                  InventoryValuationService valuation,
                                  InventoryGlPoster glPoster,
                                  ProductService productService,
                                  LocationResolver locationResolver,
                                  WarehouseNumberGenerator numberGenerator,
                                  OutboxPublisher outbox,
                                  ScopeGuard scopeGuard,
                                  AuditService audit) {
        this.counts           = counts;
        this.countLines       = countLines;
        this.onHands          = onHands;
        this.movements        = movements;
        this.posting          = posting;
        this.valuation        = valuation;
        this.glPoster         = glPoster;
        this.productService   = productService;
        this.locationResolver = locationResolver;
        this.numberGenerator  = numberGenerator;
        this.outbox           = outbox;
        this.scopeGuard       = scopeGuard;
        this.audit            = audit;
        this.unitResolver     = new StockUnitResolver(productService);
    }

    // -------------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------------

    @Override
    public StockCountDto create(CreateStockCountRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal.companyId());

        StockLocation loc = locationResolver.resolveLocation(
                request.locationUid(), principal.companyId());
        // STK-23: in-transit stock is on the road, not on a shelf — and posting a count variance
        // there would corrupt every transfer still waiting to be received.
        if (locationResolver.isInTransitLocation(loc)) {
            throw new IllegalArgumentException(
                    "The in-transit location can't be counted. Choose a store or warehouse.");
        }

        String type   = request.countType() != null ? request.countType().toUpperCase() : "FULL";
        // STK-13: a CYCLE count with no products used to fall through to the whole location —
        // the storekeeper asked for 10 fast movers and got a sheet of every product. Say so instead.
        if ("CYCLE".equals(type) && (request.productUids() == null || request.productUids().isEmpty())) {
            throw new IllegalArgumentException(
                    "Choose the products to count for a cycle count, or use a FULL count.");
        }
        String number = numberGenerator.nextCount(principal.companyId());

        StockCount count = new StockCount(
                principal.companyId(), loc.getBranchId(), number, type,
                loc.getId(), request.countDate(), request.notes(), principal.userId());
        counts.save(count);

        // Snapshot on-hand rows at this location
        List<StockOnHand> onHandRows = resolveSnapshotRows(
                principal.companyId(), loc, type, request.productUids());

        short lineNo = 0;
        for (StockOnHand soh : onHandRows) {
            lineNo++;
            // Denormalise product code/name on the line for immutability (ADR-0028 D-6).
            // Use getById(Long) — passing the numeric id to getByUid caused NotFoundException
            // inside a nested @Transactional(readOnly=true), marking the outer TX rollback-only
            // (UnexpectedRollbackException). getById does a plain findById — no scope check,
            // no exception on miss — so it never poisons the outer transaction.
            String code = String.valueOf(soh.getProductId());
            String name = code;
            String unitName = null;
            try {
                var p = productService.getById(soh.getProductId());
                if (p != null) { code = p.code(); name = p.name(); unitName = p.baseUnitName(); }
            } catch (Exception ignored) { /* product not found: keep numeric defaults */ }

            StockCountLine line = new StockCountLine(
                    count.getId(), principal.companyId(), loc.getBranchId(), lineNo,
                    soh.getProductId(), code, name,
                    // STK-08: system and counted quantities are in BASE units — name it, so the Unit
                    // column stops being blank. unitId stays null (ProductDto exposes no unit id).
                    null, unitName,
                    soh.getQuantity(), BASE_CURRENCY, principal.userId());
            countLines.save(line);
        }

        count.freeze(principal.userId());
        counts.save(count);

        audit.record(AuditEvent.of(AuditActions.STOCK_COUNT_CREATE, "stock_counts",
                        count.getId(), count.getUid())
                .detail(Map.of("number", number, "type", type,
                        "locationId", String.valueOf(loc.getId()))));

        return toDto(count, countLines.findByStockCountIdOrderByLineNoAsc(count.getId()));
    }

    // -------------------------------------------------------------------------
    // enterCount
    // -------------------------------------------------------------------------

    @Override
    public StockCountDto enterCount(String countUid, EnterCountRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        StockCount count = findAndAssertScope(countUid, principal);

        if (count.getStatus() != StockCountStatus.COUNTING) {
            // countUid intentionally not surfaced (error-hygiene rule)
            throw new IllegalStateException(
                    "This stock count is not in COUNTING status (current status: " + count.getStatus() + ").");
        }

        for (EnterCountRequest.LineEntry entry : request.lines()) {
            StockCountLine line = countLines.findById(entry.lineId())
                    .orElseThrow(() -> NotFoundException.of("StockCountLine",
                            String.valueOf(entry.lineId())));
            if (!line.getStockCountId().equals(count.getId())) {
                // entry.lineId() and countUid intentionally not surfaced (error-hygiene rule)
                throw new IllegalArgumentException(
                        "One or more count lines do not belong to the specified stock count.");
            }
            // STK-08 / OPN-01: "4 cartons" is stored as the base quantity those cartons contain.
            BigDecimal counted = entry.countedQty();
            if (entry.unitUid() != null && !entry.unitUid().isBlank()) {
                var product = productService.getById(line.getProductId());
                if (product == null) {
                    throw new IllegalArgumentException(
                            "One of the counted items no longer exists. Enter it in its base unit.");
                }
                counted = unitResolver.toBase(product, counted, entry.unitUid());
            }
            line.enterCount(counted, entry.reasonCode(), principal.userId());
            countLines.save(line);
        }

        audit.record(AuditEvent.of(AuditActions.STOCK_COUNT_ENTER, "stock_counts",
                count.getId(), count.getUid())
                .detail(Map.of("linesEntered", String.valueOf(request.lines().size()))));

        return toDto(count, countLines.findByStockCountIdOrderByLineNoAsc(count.getId()));
    }

    // -------------------------------------------------------------------------
    // post
    // -------------------------------------------------------------------------

    @Override
    public StockCountDto post(String countUid, LocalDate postingDate) {
        RequestContext.Principal principal = RequestContext.get();
        StockCount count = findAndAssertScope(countUid, principal);

        if (count.getStatus() != StockCountStatus.COUNTING) {
            // countUid intentionally not surfaced (error-hygiene rule)
            throw new IllegalStateException(
                    "This stock count must be in COUNTING status before it can be posted.");
        }

        List<StockCountLine> lines = countLines.findByStockCountIdOrderByLineNoAsc(count.getId());
        Long locationId = count.getLocationId();
        Long branchId   = count.getBranchId();
        Long companyId  = count.getCompanyId();

        // Accumulate net variance value for the single GL journal (D-6 decision).
        // Positive = net increase. Negative = net decrease.
        BigDecimal netVarianceValue = BigDecimal.ZERO;
        boolean    anyVariance      = false;

        for (StockCountLine line : lines) {
            if (line.getCountedQty() == null) {
                continue; // skip lines without a counted qty
            }

            Optional<StockOnHand> sohOpt = onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                    companyId, branchId, locationId, line.getProductId());
            BigDecimal liveQty = sohOpt.map(StockOnHand::getQuantity).orElse(BigDecimal.ZERO);

            // STK-02: the counted quantity describes the shelf at the moment the line was
            // counted, so compare it with what the system held at that same moment — live
            // on-hand minus whatever moved at this location since (sales, receipts, transfers
            // between counting and posting). Comparing with live instead turned every sale made
            // after counting into phantom stock (and a phantom GL gain) when the count posted.
            // The adjustment still lands on live: live + variance = counted + later movements.
            BigDecimal systemAtCount = systemQtyWhenCounted(line, companyId, branchId, locationId, liveQty);
            BigDecimal varianceQty   = line.getCountedQty().subtract(systemAtCount);

            if (systemAtCount.compareTo(line.getSystemQty()) != 0) {
                log.info("StockCount: snapshot qty ({}) differs from system qty when counted ({}; live {}) " +
                        "for product {} in count {} — variance measured as at the count (STK-02)",
                        line.getSystemQty(), systemAtCount, liveQty, line.getProductId(), countUid);
            }

            BigDecimal avgCost    = sohOpt.map(StockOnHand::getAvgCost).orElse(null);
            String     reasonCode = line.getReasonCode() != null
                    ? line.getReasonCode()
                    : AdjustmentReason.COUNT_CORRECTION.name();

            if (varianceQty.compareTo(BigDecimal.ZERO) == 0) {
                line.applyPost(varianceQty, avgCost, BigDecimal.ZERO,
                        reasonCode, null, principal.userId());
                countLines.save(line);
                // I6: stamp last_counted_at even when there is nothing to post (no variance).
                sohOpt.ifPresent(soh -> soh.markCounted(Instant.now(), principal.userId()));
                continue;
            }

            anyVariance = true;
            BigDecimal varianceAbsValue  = avgCost != null
                    ? varianceQty.abs().multiply(avgCost).setScale(SCALE, RM)
                    : BigDecimal.ZERO;
            boolean    decrease          = varianceQty.compareTo(BigDecimal.ZERO) < 0;
            BigDecimal varianceSignedVal = decrease ? varianceAbsValue.negate() : varianceAbsValue;
            // (reassigned below to the value the valuation engine actually applied)
            // posting.post value_amount: positive = increase value, negative = decrease value
            BigDecimal postingValue = varianceSignedVal;

            String movementUid = posting.post(
                    companyId, branchId, locationId, line.getProductId(),
                    varianceQty, MovementType.ADJUSTMENT,
                    null, "STOCK_COUNT", count.getUid(),
                    reasonCode, null, Instant.now(), principal.userId(),
                    avgCost, postingValue);

            // Revalue via ADR-0020 engine; re-read fresh SOH after posting delta applied.
            // LBO-03: VALUE ONLY — no per-line GL entry. The count's single net-variance journal
            // below is the one GL effect (D-6: "one journal per count"); the GL-posting
            // revalueAdjustment used here before booked every variance to the GL twice.
            StockOnHand freshSoh = onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                    companyId, branchId, locationId, line.getProductId())
                    .orElse(sohOpt.orElse(null));
            BigDecimal appliedValue = null;
            if (freshSoh != null) {
                appliedValue = valuation.revalueAdjustmentWithoutGl(movementUid, freshSoh, varianceQty);
                // I6: stamp last_counted_at on the authoritative (post-posting) row.
                freshSoh.markCounted(Instant.now(), principal.userId());
            }
            // The journal must move the GL by exactly what on_hand_value moved, so the
            // Σ on_hand_value == Inventory GL tie holds: take the engine's applied value, and
            // nothing when it revalued nothing (no avg cost yet / no on-hand row).
            varianceSignedVal = appliedValue != null ? appliedValue : BigDecimal.ZERO;

            netVarianceValue = netVarianceValue.add(varianceSignedVal);

            line.applyPost(varianceQty, avgCost, varianceSignedVal,
                    reasonCode, movementUid, principal.userId());
            countLines.save(line);
        }

        // One net-variance GL journal for the whole count (D-6 decision: "one journal per count preferred")
        String glEntryUid = null;
        if (anyVariance && netVarianceValue.compareTo(BigDecimal.ZERO) != 0) {
            boolean    netDecrease = netVarianceValue.compareTo(BigDecimal.ZERO) < 0;
            BigDecimal absNet      = netVarianceValue.abs();
            // This may throw on missing GL config — intentional (BR-INV-12)
            // FOLLOW-001: use countNumber as sourceRef-label and as memo descriptor; pass
            //             reasonCode "COUNT_CORRECTION" so memo never embeds a raw ULID.
            var glResult = glPoster.postAdjustmentDirect(
                    companyId, branchId, postingDate,
                    new InventoryGlPoster.AdjustmentPostCmd(
                            count.getUid(), BASE_CURRENCY, absNet, netDecrease, principal.userId(),
                            null, null,
                            count.getCountNumber(), "COUNT_CORRECTION"));
            if (glResult != null) glEntryUid = glResult.uid();
        }

        count.markPosted(glEntryUid, principal.userId());
        counts.save(count);

        // Outbox event (informational, D-12)
        outbox.publish(DomainEventType.STOCK_COUNT_POSTED,
                DomainEventType.AGG_STOCK_COUNT,
                count.getId(), count.getUid(),
                companyId, branchId,
                new StockCountPostedPayload(count.getUid(), companyId, branchId,
                        locationId, glEntryUid, netVarianceValue, Instant.now()));

        audit.record(AuditEvent.of(AuditActions.STOCK_COUNT_POST, "stock_counts",
                        count.getId(), count.getUid())
                .detail(Map.of(
                        "glEntryUid",       glEntryUid != null ? glEntryUid : "none",
                        "netVarianceValue", netVarianceValue.toPlainString())));

        return toDto(count, lines);
    }

    // -------------------------------------------------------------------------
    // cancel
    // -------------------------------------------------------------------------

    @Override
    public StockCountDto cancel(String countUid) {
        RequestContext.Principal principal = RequestContext.get();
        StockCount count = findAndAssertScope(countUid, principal);

        if (count.getStatus() == StockCountStatus.POSTED) {
            throw new IllegalStateException("A POSTED count cannot be cancelled.");
        }
        count.cancel(principal.userId());
        counts.save(count);

        audit.record(AuditEvent.of(AuditActions.STOCK_COUNT_CANCEL, "stock_counts",
                count.getId(), count.getUid()).detail(Map.of()));
        return toDto(count, countLines.findByStockCountIdOrderByLineNoAsc(count.getId()));
    }

    // -------------------------------------------------------------------------
    // reads
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public StockCountDto getByUid(String countUid) {
        RequestContext.Principal principal = RequestContext.get();
        StockCount count = findAndAssertScope(countUid, principal);
        return toDto(count, countLines.findByStockCountIdOrderByLineNoAsc(count.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockCountDto> list(Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal.companyId());
        return counts.findByCompanyIdAndBranchId(
                        principal.companyId(), principal.branchId(), pageable)
                .map(c -> toDto(c, List.of()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * The system quantity at this location as at the moment the line's count was entered
     * (STK-02): live on-hand minus the net movements recorded after it. A line's
     * {@code updated_at} is stamped by {@link StockCountLine#enterCount} and is not touched again
     * until this post, so it is the count-entry time; a re-entry (a corrected figure) moves it to
     * the re-entry. With no timestamp (legacy row) the live quantity is used, as before.
     */
    private BigDecimal systemQtyWhenCounted(StockCountLine line, Long companyId, Long branchId,
                                            Long locationId, BigDecimal liveQty) {
        Instant countedAt = line.getUpdatedAt();
        if (countedAt == null) {
            return liveQty;
        }
        BigDecimal movedSince = movements.sumQuantityAtLocationSince(
                companyId, branchId, locationId, line.getProductId(), countedAt);
        return movedSince != null ? liveQty.subtract(movedSince) : liveQty;
    }

    private StockCount findAndAssertScope(String uid, RequestContext.Principal principal) {
        StockCount c = counts.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("StockCount", uid));
        scopeGuard.assertCanActIn(principal, c.getCompanyId());
        return c;
    }

    private List<StockOnHand> resolveSnapshotRows(Long companyId, StockLocation loc,
                                                   String type, List<String> productUids) {
        if ("CYCLE".equals(type) && productUids != null && !productUids.isEmpty()) {
            List<StockOnHand> rows = new ArrayList<>();
            for (String pUid : productUids) {
                try {
                    var p = productService.getByUid(pUid);
                    onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                                    companyId, loc.getBranchId(), loc.getId(), p.id())
                            .ifPresent(rows::add);
                } catch (Exception ex) {
                    log.warn("StockCount: product uid {} not found or no on-hand at location {} — skipping",
                            pUid, loc.getId());
                }
            }
            return rows;
        }
        // FULL: all rows at this (company, branch, location)
        return onHands.findByCompanyIdAndBranchId(
                        companyId, loc.getBranchId(), Pageable.unpaged())
                .stream()
                .filter(soh -> loc.getId().equals(soh.getLocationId()))
                .toList();
    }

    private StockCountDto toDto(StockCount c, List<StockCountLine> lines) {
        // productUid lets the screen offer the item's pack sizes (STK-08); one lookup per product.
        Map<Long, String> productUids = new java.util.HashMap<>();
        for (StockCountLine l : lines) {
            productUids.computeIfAbsent(l.getProductId(), id -> {
                try {
                    var p = productService.getById(id);
                    return p != null ? p.uid() : null;
                } catch (Exception e) {
                    return null;
                }
            });
        }
        List<StockCountLineDto> lineDtos = lines.stream()
                .map(l -> new StockCountLineDto(
                        l.getId(), l.getUid(), l.getLineNo(),
                        l.getProductId(), l.getProductCode(), l.getProductName(),
                        l.getUnitName(), l.getSystemQty(), l.getCountedQty(),
                        l.getVarianceQty(), l.getUnitCostAmount(), l.getVarianceValue(),
                        l.getReasonCode(), l.getMovementUid(), l.getCurrency(),
                        productUids.get(l.getProductId())))
                .toList();
        return new StockCountDto(
                c.getId(), c.getUid(), c.getCompanyId(), c.getBranchId(),
                c.getCountNumber(), c.getStatus(), c.getCountType(),
                c.getLocationId(), c.getCountDate(),
                c.getFrozenAt(), c.getPostedAt(), c.getCancelledAt(),
                c.getVarianceGlEntryUid(), c.getNotes(), lineDtos);
    }
}
