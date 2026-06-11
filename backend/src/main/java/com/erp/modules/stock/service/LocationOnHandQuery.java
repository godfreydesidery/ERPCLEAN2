package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.LocationOnHandRowDto;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side query: per-location on-hand view (ADR-0028 D-8, FR-INVD-05/06).
 *
 * <p>Joins stock_on_hand rows to stock_locations for presentation; cross-module product
 * code/name enrichment is intentionally omitted at this layer (callers may enrich from
 * {@link com.erp.modules.products.service.ProductService} in the controller if needed).
 */
@Component
public class LocationOnHandQuery {

    private final StockOnHandRepository    onHands;
    private final StockLocationRepository  locations;
    private final ScopeGuard               scopeGuard;

    public LocationOnHandQuery(StockOnHandRepository onHands,
                                StockLocationRepository locations,
                                ScopeGuard scopeGuard) {
        this.onHands   = onHands;
        this.locations = locations;
        this.scopeGuard = scopeGuard;
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

        // Build location lookup map (company + branch, ACTIVE)
        Map<Long, StockLocation> locMap = locations
                .findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(companyId, branchId,
                        MasterStatus.ACTIVE)
                .stream()
                .collect(Collectors.toMap(StockLocation::getId, l -> l));

        // All on-hand rows for this (company, branch)
        List<StockOnHand> allRows = onHands.findByCompanyIdAndBranchId(
                companyId, branchId, Pageable.unpaged()).getContent();

        List<LocationOnHandRowDto> rows = new ArrayList<>();
        for (StockOnHand soh : allRows) {
            StockLocation loc = locMap.get(soh.getLocationId());
            String locUid  = loc != null ? loc.getUid()  : String.valueOf(soh.getLocationId());
            String locCode = loc != null ? loc.getCode() : String.valueOf(soh.getLocationId());
            String locName = loc != null ? loc.getName() : String.valueOf(soh.getLocationId());

            rows.add(new LocationOnHandRowDto(
                    soh.getLocationId(), locUid, locCode, locName,
                    soh.getProductId(),
                    null, // productUid — enriched by caller if needed
                    null, // productCode
                    null, // productName
                    soh.getQuantity(),
                    soh.getOnHandValue() != null ? soh.getOnHandValue() : BigDecimal.ZERO,
                    soh.getAvgCost(),
                    "TZS"));
        }

        // Manual pagination
        int total = rows.size();
        int from  = (int) Math.min(pageable.getOffset(), total);
        int to    = (int) Math.min(pageable.getOffset() + pageable.getPageSize(), total);
        return new PageImpl<>(rows.subList(from, to), pageable, total);
    }

    /**
     * Per-location on-hand for a specific product at a company.
     */
    @Transactional(readOnly = true)
    public List<LocationOnHandRowDto> queryForProduct(Long companyId, Long productId) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);

        List<StockOnHand> rows = onHands.findByCompanyIdAndProductId(companyId, productId);
        List<LocationOnHandRowDto> result = new ArrayList<>();
        for (StockOnHand soh : rows) {
            result.add(new LocationOnHandRowDto(
                    soh.getLocationId(), null, null, null,
                    soh.getProductId(), null, null, null,
                    soh.getQuantity(),
                    soh.getOnHandValue() != null ? soh.getOnHandValue() : BigDecimal.ZERO,
                    soh.getAvgCost(), "TZS"));
        }
        return result;
    }
}
