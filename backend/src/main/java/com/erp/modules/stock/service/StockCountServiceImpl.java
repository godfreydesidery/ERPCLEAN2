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
 * <p>Post: recompute variance against LIVE on-hand (BR-INVD-09, OQ-INVD-06); post ADJUSTMENT
 * movement per non-zero-variance line; call one {@link InventoryGlPoster#postAdjustmentDirect}
 * with the net accumulated variance for the whole count (D-6 decision).
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
    private final StockPostingService      posting;
    private final InventoryValuationService valuation;
    private final InventoryGlPoster        glPoster;
    private final ProductService           productService;
    private final LocationResolver         locationResolver;
    private final WarehouseNumberGenerator numberGenerator;
    private final OutboxPublisher          outbox;
    private final ScopeGuard               scopeGuard;
    private final AuditService             audit;

    public StockCountServiceImpl(StockCountRepository counts,
                                  StockCountLineRepository countLines,
                                  StockOnHandRepository onHands,
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
        this.posting          = posting;
        this.valuation        = valuation;
        this.glPoster         = glPoster;
        this.productService   = productService;
        this.locationResolver = locationResolver;
        this.numberGenerator  = numberGenerator;
        this.outbox           = outbox;
        this.scopeGuard       = scopeGuard;
        this.audit            = audit;
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

        String number = numberGenerator.nextCount(principal.companyId());
        String type   = request.countType() != null ? request.countType().toUpperCase() : "FULL";

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
            try {
                var p = productService.getById(soh.getProductId());
                if (p != null) { code = p.code(); name = p.name(); }
            } catch (Exception ignored) { /* product not found: keep numeric defaults */ }

            StockCountLine line = new StockCountLine(
                    count.getId(), principal.companyId(), loc.getBranchId(), lineNo,
                    soh.getProductId(), code, name,
                    null, null,
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
            throw new IllegalStateException(
                    "Count " + countUid + " is not in COUNTING status (status=" + count.getStatus() + ").");
        }

        for (EnterCountRequest.LineEntry entry : request.lines()) {
            StockCountLine line = countLines.findById(entry.lineId())
                    .orElseThrow(() -> NotFoundException.of("StockCountLine",
                            String.valueOf(entry.lineId())));
            if (!line.getStockCountId().equals(count.getId())) {
                throw new IllegalArgumentException(
                        "Line " + entry.lineId() + " does not belong to count " + countUid);
            }
            line.enterCount(entry.countedQty(), principal.userId());
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
            throw new IllegalStateException(
                    "Count " + countUid + " must be in COUNTING status to post.");
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

            // Recompute variance against LIVE on-hand at post time (OQ-INVD-06, BR-INVD-09)
            Optional<StockOnHand> sohOpt = onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                    companyId, branchId, locationId, line.getProductId());

            BigDecimal liveQty    = sohOpt.map(StockOnHand::getQuantity).orElse(BigDecimal.ZERO);
            BigDecimal varianceQty = line.getCountedQty().subtract(liveQty);

            if (liveQty.compareTo(line.getSystemQty()) != 0) {
                log.warn("StockCount: snapshot qty ({}) differs from live qty ({}) for product {} " +
                        "in count {} — recomputing variance against live (OQ-INVD-06)",
                        line.getSystemQty(), liveQty, line.getProductId(), countUid);
            }

            BigDecimal avgCost    = sohOpt.map(StockOnHand::getAvgCost).orElse(null);
            String     reasonCode = line.getReasonCode() != null
                    ? line.getReasonCode()
                    : AdjustmentReason.COUNT_CORRECTION.name();

            if (varianceQty.compareTo(BigDecimal.ZERO) == 0) {
                line.applyPost(varianceQty, avgCost, BigDecimal.ZERO,
                        reasonCode, null, principal.userId());
                countLines.save(line);
                continue;
            }

            anyVariance = true;
            BigDecimal varianceAbsValue  = avgCost != null
                    ? varianceQty.abs().multiply(avgCost).setScale(SCALE, RM)
                    : BigDecimal.ZERO;
            boolean    decrease          = varianceQty.compareTo(BigDecimal.ZERO) < 0;
            BigDecimal varianceSignedVal = decrease ? varianceAbsValue.negate() : varianceAbsValue;
            // posting.post value_amount: positive = increase value, negative = decrease value
            BigDecimal postingValue = varianceSignedVal;

            String movementUid = posting.post(
                    companyId, branchId, locationId, line.getProductId(),
                    varianceQty, MovementType.ADJUSTMENT,
                    null, "STOCK_COUNT", count.getUid(),
                    reasonCode, null, Instant.now(), principal.userId(),
                    avgCost, postingValue);

            // Revalue via ADR-0020 engine; re-read fresh SOH after posting delta applied
            StockOnHand freshSoh = onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                    companyId, branchId, locationId, line.getProductId())
                    .orElse(sohOpt.orElse(null));
            if (freshSoh != null) {
                // FOLLOW-001: pass productCode + reasonCode so per-line GL memo avoids raw ULID.
                valuation.revalueAdjustment(movementUid, freshSoh, varianceQty, postingDate,
                        null, null, line.getProductCode(), reasonCode);
            }

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

    private static StockCountDto toDto(StockCount c, List<StockCountLine> lines) {
        List<StockCountLineDto> lineDtos = lines.stream()
                .map(l -> new StockCountLineDto(
                        l.getId(), l.getUid(), l.getLineNo(),
                        l.getProductId(), l.getProductCode(), l.getProductName(),
                        l.getUnitName(), l.getSystemQty(), l.getCountedQty(),
                        l.getVarianceQty(), l.getUnitCostAmount(), l.getVarianceValue(),
                        l.getReasonCode(), l.getMovementUid(), l.getCurrency()))
                .toList();
        return new StockCountDto(
                c.getId(), c.getUid(), c.getCompanyId(), c.getBranchId(),
                c.getCountNumber(), c.getStatus(), c.getCountType(),
                c.getLocationId(), c.getCountDate(),
                c.getFrozenAt(), c.getPostedAt(), c.getCancelledAt(),
                c.getVarianceGlEntryUid(), c.getNotes(), lineDtos);
    }
}
