package com.erp.modules.stock.service;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.stock.domain.dto.LocationOnHandRowDto;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side query: per-location on-hand view (ADR-0028 D-8, FR-INVD-05/06).
 *
 * <p>Rows carry display labels, not bare ids: locations are resolved from the stock module's own
 * {@link StockLocationRepository} (intra-module) and product code/name/base-unit across the module
 * boundary via {@link ProductService} — a DTO call, never an entity or repository of another module
 * (PROJECT-CONVENTIONS §2). Enrichment is batched per distinct product id on the returned rows, and
 * for the branch view it runs on the requested page only, so the number of lookups is bounded by
 * page size rather than by the size of the branch's catalogue.
 *
 * <p>A label is left null only when the referenced master no longer exists; a deleted product must
 * degrade one row's labels, not fail the whole read.
 */
@Component
public class LocationOnHandQuery {

    private final StockOnHandRepository    onHands;
    private final StockLocationRepository  locations;
    private final ProductService           productService;
    private final ScopeGuard               scopeGuard;

    public LocationOnHandQuery(StockOnHandRepository onHands,
                                StockLocationRepository locations,
                                ProductService productService,
                                ScopeGuard scopeGuard) {
        this.onHands        = onHands;
        this.locations      = locations;
        this.productService = productService;
        this.scopeGuard     = scopeGuard;
    }

    /**
     * Returns a paged per-location on-hand view for the caller's company + branch.
     * Each row represents one (location, product) with the aggregated quantity and value.
     *
     * @param companyId tenant
     * @param branchId  branch (caller's active branch)
     * @param pageable  pagination
     */
    @Transactional(readOnly = true)
    public Page<LocationOnHandRowDto> queryForBranch(Long companyId, Long branchId,
                                                      Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);

        // All on-hand rows for this (company, branch)
        List<StockOnHand> allRows = onHands.findByCompanyIdAndBranchId(
                companyId, branchId, Pageable.unpaged()).getContent();

        // Paginate FIRST, enrich after: product labels cost one lookup per distinct product, so they
        // are resolved for the requested page only, never for the whole branch.
        int total = allRows.size();
        int from  = (int) Math.min(pageable.getOffset(), total);
        int to    = (int) Math.min(pageable.getOffset() + pageable.getPageSize(), total);
        List<StockOnHand> pageRows = allRows.subList(from, to);

        // Locations are resolved by id within the company rather than by "ACTIVE in this branch":
        // stock parked at a location that was later archived still needs a name on the row.
        Map<Long, StockLocation> locById = locationMap(companyId, pageRows);
        Map<Long, ProductDto> productById = productMap(pageRows);
        List<LocationOnHandRowDto> rows = new ArrayList<>(pageRows.size());
        for (StockOnHand soh : pageRows) {
            rows.add(row(soh, locById.get(soh.getLocationId()), productById.get(soh.getProductId())));
        }
        return new PageImpl<>(rows, pageable, total);
    }

    /**
     * Per-location on-hand for a specific product identified by uid at a company.
     *
     * <p>The product is resolved through {@link ProductService#getByUid(String)}, which scope-checks
     * the LOADED product's company (never the caller's parameter). A uid that resolves outside the
     * requested company is reported as not found — a caller must not be able to probe another
     * tenant's catalogue through the {@code companyId} query parameter.
     */
    @Transactional(readOnly = true)
    public List<LocationOnHandRowDto> queryForProductByUid(Long companyId, String productUid) {
        ProductDto product = productService.getByUid(productUid);
        if (!Objects.equals(product.companyId(), companyId)) {
            throw new NotFoundException("Product not found.");
        }
        return rowsForProduct(product.companyId(), product.id(), product);
    }

    /**
     * Per-location on-hand for a specific product at a company.
     */
    @Transactional(readOnly = true)
    public List<LocationOnHandRowDto> queryForProduct(Long companyId, Long productId) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        return rowsForProduct(companyId, productId, loadProductQuietly(productId));
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private List<LocationOnHandRowDto> rowsForProduct(Long companyId, Long productId,
                                                       ProductDto product) {
        List<StockOnHand> rows = onHands.findByCompanyIdAndProductId(companyId, productId);
        Map<Long, StockLocation> locById = locationMap(companyId, rows);
        List<LocationOnHandRowDto> result = new ArrayList<>(rows.size());
        for (StockOnHand soh : rows) {
            result.add(row(soh, locById.get(soh.getLocationId()), product));
        }
        return result;
    }

    /** One wire row: on-hand figures plus the location and product labels needed to display them. */
    private LocationOnHandRowDto row(StockOnHand soh, StockLocation loc, ProductDto product) {
        return new LocationOnHandRowDto(
                soh.getLocationId(),
                loc != null ? loc.getUid()  : null,
                loc != null ? loc.getCode() : null,
                loc != null ? loc.getName() : null,
                soh.getProductId(),
                product != null ? product.uid()  : null,
                product != null ? product.code() : null,
                product != null ? product.name() : null,
                unitLabel(product),
                soh.getQuantity(),
                soh.getOnHandValue() != null ? soh.getOnHandValue() : BigDecimal.ZERO,
                soh.getAvgCost(),
                "TZS");
    }

    /** Base-unit code, falling back to its name — quantities are meaningless without it. */
    private String unitLabel(ProductDto product) {
        if (product == null) {
            return null;
        }
        String code = product.baseUnitCode();
        if (code != null && !code.isBlank()) {
            return code;
        }
        String name = product.baseUnitName();
        return name != null && !name.isBlank() ? name : null;
    }

    /** Batch-resolve the distinct product ids on a set of rows to their DTO (one call each). */
    private Map<Long, ProductDto> productMap(List<StockOnHand> rows) {
        Map<Long, ProductDto> byId = new HashMap<>();
        rows.stream()
                .map(StockOnHand::getProductId)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(id -> byId.put(id, loadProductQuietly(id)));
        return byId;
    }

    /**
     * Product lookup that degrades to {@code null} instead of throwing: a product deleted after its
     * stock row was written must blank one row's labels, not break the whole listing.
     */
    private ProductDto loadProductQuietly(Long productId) {
        if (productId == null) {
            return null;
        }
        try {
            return productService.getById(productId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Batch-resolve the distinct {@code location_id}s on a set of on-hand rows, scoped to the
     * caller's company (never a bare {@code findById}).
     */
    private Map<Long, StockLocation> locationMap(Long companyId, List<StockOnHand> rows) {
        List<Long> locationIds = rows.stream()
                .map(StockOnHand::getLocationId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (locationIds.isEmpty()) {
            return Map.of();
        }
        return locations.findByCompanyIdAndIdIn(companyId, locationIds).stream()
                .collect(Collectors.toMap(StockLocation::getId, Function.identity()));
    }
}
