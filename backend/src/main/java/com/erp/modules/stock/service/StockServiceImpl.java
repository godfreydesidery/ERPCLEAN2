package com.erp.modules.stock.service;

import com.erp.modules.costing.domain.dto.DimensionTagDto;
import com.erp.modules.costing.domain.dto.ResolvedDimensionTagDto;
import com.erp.modules.costing.service.DimensionResolver;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.stock.domain.dto.AdjustStockRequest;
import com.erp.modules.stock.domain.dto.OpeningBalanceRequest;
import com.erp.modules.stock.domain.dto.SetReorderLevelRequest;
import com.erp.modules.stock.domain.dto.StockMovementDto;
import com.erp.modules.stock.domain.dto.StockOnHandDto;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockMovement;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual stock operations (ADR-0010 D-11): ADJUSTMENT, OPENING_BALANCE, reorder-level,
 * on-hand list, movement ledger.
 *
 * <p>Enforces:
 * <ul>
 *   <li>{@code assertCanActIn} on every read path (the anti-regression guard — brief §3.1).</li>
 *   <li>Product must be stockable (D-3); else 422.</li>
 *   <li>ADJUSTMENT requires a valid reason (D-7); the DB CHECK enforces presence, service enforces value.</li>
 *   <li>OPENING_BALANCE: no prior movements may exist for (product, active branch) (D-11).</li>
 *   <li>Branch always from RequestContext (never from request body — D-11 / ADR-0008 D-12).</li>
 *   <li>Manual ops audited (D-12); event-driven ops do not double-audit.</li>
 * </ul>
 */
@Service
@Transactional
public class StockServiceImpl implements StockService {

    private final StockOnHandRepository     onHands;
    private final StockMovementRepository   movements;
    private final StockPostingService       posting;
    private final ProductService            productService;
    private final InventoryValuationService valuation;
    private final DimensionResolver         dimensionResolver;
    private final LocationResolver          locationResolver;
    private final StockLocationRepository   locations;
    private final ScopeGuard               scopeGuard;
    private final AuditService             audit;

    public StockServiceImpl(StockOnHandRepository onHands,
                            StockMovementRepository movements,
                            StockPostingService posting,
                            ProductService productService,
                            InventoryValuationService valuation,
                            DimensionResolver dimensionResolver,
                            LocationResolver locationResolver,
                            StockLocationRepository locations,
                            ScopeGuard scopeGuard,
                            AuditService audit) {
        this.onHands           = onHands;
        this.movements         = movements;
        this.posting           = posting;
        this.productService    = productService;
        this.valuation         = valuation;
        this.dimensionResolver = dimensionResolver;
        this.locationResolver  = locationResolver;
        this.locations         = locations;
        this.scopeGuard        = scopeGuard;
        this.audit             = audit;
    }

    // -------------------------------------------------------------------------
    // Manual write ops
    // -------------------------------------------------------------------------

    @Override
    public StockMovementDto adjust(AdjustStockRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        ProductDto product = resolveStockableProduct(request.productUid(), principal);

        if (request.quantity().signum() == 0) {
            throw new IllegalArgumentException("Adjustment quantity must be non-zero.");
        }

        // An adjustment corrects the stock WHERE IT ALREADY SITS. On-hand is keyed per location, so
        // read every on-hand row for the product in this branch rather than assuming a single one:
        //  - exactly one row  → adjust THAT location (which may not be the branch default).
        //  - no row yet       → first touch; fall through to the branch default location below.
        //  - more than one    → ambiguous (which location did the count correct?). Say so plainly.
        // Resolving the location from the branch default unconditionally (as this did) posted the
        // correction to the default location while the stock lived in another — splitting the product
        // across two on-hand rows, and then failing on the single-row re-read below.
        List<StockOnHand> onHandRows = onHands.findAllByCompanyIdAndBranchIdAndProductId(
                product.companyId(), principal.branchId(), product.id());
        if (onHandRows.size() > 1) {
            throw new IllegalArgumentException(
                    "This product is held at more than one location in this branch. Adjust it from "
                  + "the location's stock screen so the correction lands on the right one.");
        }
        StockOnHand sohBefore = onHandRows.isEmpty() ? null : onHandRows.get(0);

        // FIX C (adversarial review): resolve avg_cost BEFORE posting so the movement row
        // carries unit_cost_amount + value_amount immediately (columns are immutable/updatable=false
        // on the entity — they cannot be patched after the fact). If avg_cost is null (no cost
        // established yet) pass null cost and skip the value — consistent with the D-2 null-cost edge.
        BigDecimal avgCostNow = sohBefore != null ? sohBefore.getAvgCost() : null;
        BigDecimal movementValue = null;
        if (avgCostNow != null) {
            movementValue = request.quantity().abs()
                    .multiply(avgCostNow)
                    .setScale(4, RoundingMode.HALF_UP);
            // sign: decrease → negative, increase → positive (D-2 convention)
            if (request.quantity().signum() < 0) {
                movementValue = movementValue.negate();
            }
        }

        // ADR-0025 D-6: resolve optional dimension tag before posting.
        // AdjustStockRequest carries nullable *ValueUid fields — unresolved when null.
        ResolvedDimensionTagDto dimTag = dimensionResolver.resolveTag(
                product.companyId(),
                new DimensionTagDto(
                        request.costCentreValueUid(),
                        request.departmentValueUid(),
                        null, null));

        // Post where the stock already is; ADR-0028 D-3's branch default only applies on first touch
        // (no on-hand row yet) — which is what a location-unaware caller means by "the branch".
        Long locationId = sohBefore != null && sohBefore.getLocationId() != null
                ? sohBefore.getLocationId()
                : locationResolver.defaultLocationId(product.companyId(), principal.branchId());

        String movementUid = posting.post(
                product.companyId(),
                principal.branchId(),
                locationId,
                product.id(),
                request.quantity(),
                MovementType.ADJUSTMENT,
                null, null, null,
                request.reasonCode().name(),
                request.note(),
                Instant.now(),
                principal.userId(),
                avgCostNow, movementValue,   // FIX C: carry cost on the movement row
                dimTag.costCentreValueId(), dimTag.departmentValueId());

        // Audit the manual op (D-12).
        StockMovement movement = movements.findByUid(movementUid).orElseThrow();
        audit.record(AuditEvent.of(AuditActions.STOCK_ADJUST, "stock_movements",
                movement.getId(), movement.getUid())
                .detail(Map.of(
                        "productUid",  request.productUid(),
                        "quantity",    request.quantity().toPlainString(),
                        "reasonCode",  request.reasonCode().name(),
                        "branchId",    String.valueOf(principal.branchId())
                )));

        // Revalue on_hand_value + post GL DR STOCK_ADJUSTMENT / CR INVENTORY (ADR-0020 D-7).
        // Re-read the on-hand row (posting.post may have upserted it if it was absent) — by LOCATION,
        // the key on-hand is actually stored under. The branch-wide finder would throw here the moment
        // a product were held at a second location.
        StockOnHand soh = onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                product.companyId(), principal.branchId(), locationId, product.id()).orElse(null);
        if (soh != null) {
            // FOLLOW-001: pass productCode + reasonCode so memo text avoids raw ULID.
            valuation.revalueAdjustment(movementUid, soh, request.quantity(), LocalDate.now(),
                    dimTag.costCentreValueId(), dimTag.departmentValueId(),
                    product.code(), request.reasonCode() != null ? request.reasonCode().name() : null);
        }

        return StockMovementDto.from(movement);
    }

    @Override
    public StockMovementDto openingBalance(OpeningBalanceRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        ProductDto product = resolveStockableProduct(request.productUid(), principal);

        // Opening balance is only for a never-tracked (product, active branch).
        if (onHands.existsByCompanyIdAndBranchIdAndProductId(
                product.companyId(), principal.branchId(), product.id())) {
            throw new IllegalStateException(
                    "An on-hand record already exists for this product at the active branch. " +
                    "Use an ADJUSTMENT to correct an existing level.");
        }

        // ADR-0028 D-3: resolve the branch's default location for location-unaware callers.
        Long locationId = locationResolver.defaultLocationId(product.companyId(), principal.branchId());

        String movementUid = posting.post(
                product.companyId(),
                principal.branchId(),
                locationId,
                product.id(),
                request.quantity(),   // must be positive (validated by @Positive on the DTO)
                MovementType.OPENING_BALANCE,
                null, null, null,
                null, request.note(),
                Instant.now(),
                principal.userId(),
                null, null);  // cost not set here — use InventoryValuationService.setOpeningValue

        StockMovement movement = movements.findByUid(movementUid).orElseThrow();
        audit.record(AuditEvent.of(AuditActions.STOCK_OPENING, "stock_movements",
                movement.getId(), movement.getUid())
                .detail(Map.of(
                        "productUid", request.productUid(),
                        "quantity",   request.quantity().toPlainString(),
                        "branchId",   String.valueOf(principal.branchId())
                )));

        return StockMovementDto.from(movement);
    }

    @Override
    public StockOnHandDto setReorderLevel(String uid, SetReorderLevelRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        StockOnHand soh = onHands.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("StockOnHand", uid));
        scopeGuard.assertCanActIn(principal, soh.getCompanyId());

        if (request.reorderLevel() != null && request.reorderLevel().signum() < 0) {
            throw new IllegalArgumentException("Reorder level must be >= 0 when set.");
        }

        soh.setReorderLevel(request.reorderLevel(), principal.userId());
        onHands.save(soh);

        audit.record(AuditEvent.of(AuditActions.STOCK_REORDER_SET, "stock_on_hand",
                soh.getId(), soh.getUid())
                .detail(Map.of(
                        "reorderLevel", request.reorderLevel() != null
                                ? request.reorderLevel().toPlainString() : "null",
                        "branchId", String.valueOf(soh.getBranchId())
                )));

        ProductDto product = productService.getById(soh.getProductId());
        StockLocation location = locations
                .findByCompanyIdAndId(soh.getCompanyId(), soh.getLocationId())
                .orElse(null);
        return StockOnHandDto.from(soh, product.code(), product.name(),
                location != null ? location.getUid()  : null,
                location != null ? location.getName() : null);
    }

    // -------------------------------------------------------------------------
    // Read ops — assertCanActIn on EVERY read path
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<StockOnHandDto> listOnHand(String q, Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        if (principal == null || principal.companyId() == null || principal.branchId() == null) {
            throw new IllegalStateException("No active company/branch in request context.");
        }
        scopeGuard.assertCanActIn(principal, principal.companyId());

        if (q == null || q.isBlank()) {
            Page<StockOnHand> page = onHands.findByCompanyIdAndBranchId(
                    principal.companyId(), principal.branchId(), pageable);
            return enrichPage(page, principal.companyId());
        }

        // Resolve the product ids matching the search term WITHIN company scope via the products
        // module (code/name case-insensitive contains) — no cross-module entity join (D-1 boundary).
        List<ProductDto> matchedProducts = productService
                .list(principal.companyId(), q.trim(), Pageable.unpaged())
                .getContent();
        if (matchedProducts.isEmpty()) {
            return Page.empty(pageable);
        }
        List<Long> productIds = matchedProducts.stream().map(ProductDto::id).toList();
        Map<Long, ProductDto> productById = matchedProducts.stream()
                .collect(Collectors.toMap(ProductDto::id, Function.identity()));

        Page<StockOnHand> page = onHands.findByCompanyIdAndBranchIdAndProductIdIn(
                principal.companyId(), principal.branchId(), productIds, pageable);
        Map<Long, StockLocation> locationById = locationMap(principal.companyId(), page.getContent());
        return page.map(s -> {
            ProductDto p = productById.get(s.getProductId());
            StockLocation loc = locationById.get(s.getLocationId());
            return StockOnHandDto.from(s,
                    p != null ? p.code() : null,
                    p != null ? p.name() : null,
                    loc != null ? loc.getUid()  : null,
                    loc != null ? loc.getName() : null);
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockMovementDto> listMovements(String productUid, Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        ProductDto product = productService.getByUid(productUid);
        scopeGuard.assertCanActIn(principal, product.companyId());

        return movements.findByCompanyIdAndBranchIdAndProductIdOrderByOccurredAtAsc(
                        product.companyId(), principal.branchId(), product.id(), pageable)
                .map(StockMovementDto::from);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Batch-enrich a page of {@link StockOnHand} rows with product code + name and location
     * uid + name. Fetches each product by id via {@link ProductService#getById} — one call per
     * distinct product on the page (typically ≤ page-size, usually 20–50). No cross-module entity
     * join. Location resolution is an intra-module lookup (both stock-owned) — see
     * {@link #locationMap}.
     */
    private Page<StockOnHandDto> enrichPage(Page<StockOnHand> page, Long companyId) {
        // Collect distinct product ids on this page, then bulk-resolve via ProductService.
        Map<Long, ProductDto> productById = page.getContent().stream()
                .map(StockOnHand::getProductId)
                .distinct()
                .collect(Collectors.toMap(
                        Function.identity(),
                        id -> {
                            try {
                                return productService.getById(id);
                            } catch (Exception e) {
                                // Product was deleted after stock row was written — degrade
                                // gracefully rather than breaking the entire list response.
                                return null;
                            }
                        }
                ));
        Map<Long, StockLocation> locationById = locationMap(companyId, page.getContent());
        return page.map(s -> {
            ProductDto p = productById.get(s.getProductId());
            StockLocation loc = locationById.get(s.getLocationId());
            return StockOnHandDto.from(s,
                    p != null ? p.code() : null,
                    p != null ? p.name() : null,
                    loc != null ? loc.getUid()  : null,
                    loc != null ? loc.getName() : null);
        });
    }

    /**
     * Batch-resolve the distinct {@code location_id}s on a set of on-hand rows to their
     * {@link StockLocation} (uid + name), scoped to the caller's company (TenantScopingRulesTest —
     * uses {@code findByCompanyIdAndIdIn}, never a bare {@code findById}).
     */
    private Map<Long, StockLocation> locationMap(Long companyId, List<StockOnHand> rows) {
        List<Long> locationIds = rows.stream()
                .map(StockOnHand::getLocationId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (locationIds.isEmpty()) {
            return Map.of();
        }
        return locations.findByCompanyIdAndIdIn(companyId, locationIds).stream()
                .collect(Collectors.toMap(
                        StockLocation::getId,
                        Function.identity()));
    }

    /**
     * Resolve a product by uid and assert: product exists, belongs to principal's company,
     * and is stockable (D-3/D-9). Throws 404 / 403 / 422 appropriately.
     */
    private ProductDto resolveStockableProduct(String productUid,
                                               RequestContext.Principal principal) {
        ProductDto product = productService.getByUid(productUid);
        scopeGuard.assertCanActIn(principal, product.companyId());
        // BR-STOCK-02: only stockable products may have stock movements
        if (!product.stockable()) {
            throw new IllegalArgumentException(
                    "The selected product is not set up for stock tracking and cannot be used in stock operations.");
        }
        return product;
    }
}
