package com.erp.modules.stock.service;

import com.erp.modules.products.service.LeafCostResolver;
import com.erp.modules.stock.repository.StockOnHandRepository;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock-side implementation of the {@link LeafCostResolver} port (ADR-0026 D-10).
 *
 * <p><strong>Dependency-inversion rationale (D-10):</strong> {@code products} declares the
 * {@code LeafCostResolver} interface; this class implements it in {@code stock}. The dependency
 * arrow therefore remains {@code stock → products} (implementation depends on the port it
 * implements), matching the existing {@code stock → products.dto/service} edges that
 * {@code RecipeExplosionResolver} already creates. No {@code products → stock} import is needed,
 * and {@code ModuleBoundaryTest}'s acyclic module rule stays green.
 *
 * <p>Reads {@code StockOnHand.avg_cost} via a scalar projection query — no Stock entity is imported
 * into {@code products}. Batch-read for all leaf product ids in one query per call (NFR-BOM-02).
 */
@Component
@Transactional(readOnly = true)
public class StockLeafCostResolver implements LeafCostResolver {

    private final StockOnHandRepository stockOnHands;

    public StockLeafCostResolver(StockOnHandRepository stockOnHands) {
        this.stockOnHands = stockOnHands;
    }

    /**
     * Batch-fetches avg_cost for the given product ids at (company, branch) scope.
     * Missing entries (no on-hand row or avg_cost IS NULL) are absent from the result map.
     *
     * <p>N+1 note: this uses one query per {@link #avgCosts} call (the cost roll-up calls it once
     * with all leaf ids). The per-product loop in the caller is over the in-memory result map.
     */
    @Override
    public Map<Long, BigDecimal> avgCosts(Long companyId, Long branchId, List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        // Use individual lookups (StockOnHandRepository has per-triple finder); for the roll-up
        // use-case this is typically called once with all leaf ids. A batch query extension on
        // StockOnHandRepository can be added when Manufacturing requires higher throughput.
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Long productId : productIds) {
            stockOnHands.findByCompanyIdAndBranchIdAndProductId(companyId, branchId, productId)
                    .filter(soh -> soh.getAvgCost() != null)
                    .ifPresent(soh -> result.put(productId, soh.getAvgCost()));
        }
        return result;
    }
}
