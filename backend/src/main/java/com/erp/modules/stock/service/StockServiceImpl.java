package com.erp.modules.stock.service;

import com.erp.modules.costing.domain.dto.DimensionTagDto;
import com.erp.modules.costing.domain.dto.ResolvedDimensionTagDto;
import com.erp.modules.costing.service.DimensionResolver;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.stock.domain.dto.AdjustStockRequest;
import com.erp.modules.stock.domain.dto.OpeningBalanceRequest;
import com.erp.modules.stock.domain.dto.SetOpeningValuationRequest;
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
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final AdjustTargetResolver     adjustTargets;
    private final StockUnitResolver        unitResolver;
    private final PermissionResolver       permissionResolver;

    /** Gates the opening VALUE leg (it posts to the GL) — same rule as the bulk stock sheet. */
    private static final String PERM_OPENING_SET = "INVENTORY.OPENING.SET";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    public StockServiceImpl(StockOnHandRepository onHands,
                            StockMovementRepository movements,
                            StockPostingService posting,
                            ProductService productService,
                            InventoryValuationService valuation,
                            DimensionResolver dimensionResolver,
                            LocationResolver locationResolver,
                            StockLocationRepository locations,
                            ScopeGuard scopeGuard,
                            AuditService audit,
                            AdjustTargetResolver adjustTargets,
                            StockUnitResolver unitResolver,
                            PermissionResolver permissionResolver) {
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
        this.adjustTargets     = adjustTargets;
        this.unitResolver      = unitResolver;
        this.permissionResolver = permissionResolver;
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
        // STK-08 / OPN-01: the operator may state the quantity in a pack size ("2 cartons"); stock
        // is always posted in base units. Null unit = base, exactly as before.
        StockUnitResolver.Uom uom = unitResolver.resolve(product, request.unitUid());
        final BigDecimal qty = request.quantity().multiply(uom.factorToBase());

        // An adjustment corrects the stock WHERE IT ALREADY SITS. On-hand is keyed per location, so
        // the target is chosen from the product's on-hand rows in this branch (AdjustTargetResolver):
        // an explicit locationUid wins; otherwise the one location that actually holds it, or the
        // branch default on first touch. Resolving from the branch default unconditionally (as this
        // once did) split a product across two rows; refusing whenever more than one ROW existed
        // (as it did until STK-01) blocked every product that had ever arrived by transfer, because
        // receipt leaves a zero-quantity row behind at the in-transit location.
        AdjustTargetResolver.Target target = adjustTargets.resolve(
                product.companyId(), principal.branchId(), product.id(), request.locationUid());
        StockOnHand sohBefore = target.onHand();

        // STK-15: a decrease may not take the location below zero unless the location allows
        // negative stock — the same location-level rule transfers already enforce. A typo of −120
        // for −12 used to drive the shelf to −108 silently. Checked against ON-HAND, not available:
        // reservations are soft (over-reservation is allowed), and goods that are physically broken
        // must be writable-off even when a sales order has reserved them.
        if (qty.signum() < 0 && !locationResolver.isAllowNegative(target.locationId())) {
            BigDecimal current = sohBefore != null && sohBefore.getQuantity() != null
                    ? sohBefore.getQuantity() : BigDecimal.ZERO;
            if (current.add(qty).signum() < 0) {
                throw new ConflictException(
                        "Not enough stock to remove " + qty.abs().stripTrailingZeros().toPlainString()
                      + baseUnitSuffix(product)
                      + " — only " + current.max(BigDecimal.ZERO).stripTrailingZeros().toPlainString()
                      + " on hand at this location, which does not allow negative stock.");
            }
        }

        // FIX C (adversarial review): resolve avg_cost BEFORE posting so the movement row
        // carries unit_cost_amount + value_amount immediately (columns are immutable/updatable=false
        // on the entity — they cannot be patched after the fact). If avg_cost is null (no cost
        // established yet) pass null cost and skip the value — consistent with the D-2 null-cost edge.
        BigDecimal avgCostNow = sohBefore != null ? sohBefore.getAvgCost() : null;
        BigDecimal movementValue = null;
        if (avgCostNow != null) {
            movementValue = qty.abs()
                    .multiply(avgCostNow)
                    .setScale(4, RoundingMode.HALF_UP);
            // sign: decrease → negative, increase → positive (D-2 convention)
            if (qty.signum() < 0) {
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
        Long locationId = target.locationId();

        String movementUid = posting.post(
                product.companyId(),
                principal.branchId(),
                locationId,
                product.id(),
                qty,
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
                        "quantity",    qty.toPlainString(),
                        "enteredQty",  request.quantity().toPlainString(),
                        "enteredUnit", String.valueOf(uom.name()),
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
            valuation.revalueAdjustment(movementUid, soh, qty, LocalDate.now(),
                    dimTag.costCentreValueId(), dimTag.departmentValueId(),
                    product.code(), request.reasonCode() != null ? request.reasonCode().name() : null);
        }

        return StockMovementDto.from(movement);
    }

    @Override
    public StockMovementDto openingBalance(OpeningBalanceRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        ProductDto product = resolveStockableProduct(request.productUid(), principal);

        // Opening balance is only for a never-tracked (product, active branch): no movement of any
        // kind yet (D-11). STK-17: this used to test for an on-hand ROW, which a sales-order
        // reservation creates without moving stock, and the screen then claimed "an opening balance
        // already exists" when none had ever been entered. Say what actually blocks it.
        if (movements.existsByCompanyIdAndBranchIdAndProductId(
                product.companyId(), principal.branchId(), product.id())) {
            throw new IllegalStateException(
                    "This product already has stock activity at this branch (a sale, receipt, "
                  + "transfer or adjustment), so an opening balance can no longer be entered. "
                  + "Use Adjust Stock to correct the quantity.");
        }

        // STK-08 / OPN-01: quantity may be stated in a pack size; posted in base units.
        StockUnitResolver.Uom uom = unitResolver.resolve(product, request.unitUid());
        BigDecimal qty = request.quantity().multiply(uom.factorToBase());

        // PRD-07 / LSF-09: the opening cost, per BASE unit. An explicit cost is the operator's
        // statement and needs the opening-valuation permission: refused up front, before anything
        // is posted, rather than silently dropped (the bulk sheet's rule). With no cost given, the
        // product's own cost is used when the caller may value; otherwise the quantity is posted
        // unvalued, exactly as before.
        boolean mayValue = permissionResolver.hasPermission(
                principal, PERM_OPENING_SET, System.currentTimeMillis());
        BigDecimal baseCost = null;
        if (request.unitCost() != null) {
            if (!mayValue) {
                throw new IllegalArgumentException(
                        "Setting an opening cost needs the opening-valuation permission. Leave the "
                      + "cost blank, or ask an administrator to grant it.");
            }
            baseCost = request.unitCost().divide(uom.factorToBase(), 4, RoundingMode.HALF_UP);
        } else if (mayValue) {
            baseCost = productCost(product);
        }

        // ADR-0028 D-3: resolve the branch's default location for location-unaware callers.
        Long locationId = locationResolver.defaultLocationId(product.companyId(), principal.branchId());

        String movementUid = posting.post(
                product.companyId(),
                principal.branchId(),
                locationId,
                product.id(),
                qty,   // positive: @Positive on the DTO, pack factor > 0
                MovementType.OPENING_BALANCE,
                null, null, null,
                null, request.note(),
                Instant.now(),
                principal.userId(),
                null, null);  // cost is set below through the one-time opening valuation

        StockMovement movement = movements.findByUid(movementUid).orElseThrow();
        audit.record(AuditEvent.of(AuditActions.STOCK_OPENING, "stock_movements",
                movement.getId(), movement.getUid())
                .detail(Map.of(
                        "productUid", request.productUid(),
                        "quantity",   qty.toPlainString(),
                        "enteredQty", request.quantity().toPlainString(),
                        "enteredUnit", String.valueOf(uom.name()),
                        "branchId",   String.valueOf(principal.branchId())
                )));

        // Value it in the same transaction (DR Inventory / CR Opening Balance Equity), the way the
        // bulk stock sheet does. Skipped when the row already carries a cost: the valuation is
        // one-time per row and is never overwritten.
        if (baseCost != null) {
            StockOnHand soh = onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                    product.companyId(), principal.branchId(), locationId, product.id()).orElse(null);
            if (soh != null && soh.getAvgCost() == null
                    && soh.getOnHandValue().compareTo(BigDecimal.ZERO) == 0) {
                valuation.setOpeningValue(new SetOpeningValuationRequest(soh.getUid(), baseCost),
                        LocalDate.now(BUSINESS_ZONE));
            }
        }

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

    /** " Pieces"-style suffix naming the base unit, or empty when the product has none. */
    private static String baseUnitSuffix(ProductDto product) {
        return product.baseUnitName() != null && !product.baseUnitName().isBlank()
                ? " " + product.baseUnitName() : "";
    }

    /** The product's own cost per base unit; null when unset, zero or not a number. */
    private static BigDecimal productCost(ProductDto product) {
        if (product.cost() == null || product.cost().amount() == null
                || product.cost().amount().isBlank()) {
            return null;
        }
        try {
            BigDecimal c = new BigDecimal(product.cost().amount().trim().replace(",", ""));
            // Zero is "never priced", not "free": valuing at 0 would lock the one-time valuation.
            return c.signum() > 0 ? c : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
