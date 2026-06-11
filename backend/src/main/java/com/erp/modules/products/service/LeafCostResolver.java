package com.erp.modules.products.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Port for resolving the average cost of leaf (BUY/raw) components, declared in {@code products}
 * and implemented in {@code stock} (ADR-0026 D-10 — dependency-inversion pattern).
 *
 * <p><strong>Cycle-prevention rationale (D-10):</strong> {@code stock} already depends on
 * {@code products} (the {@code RecipeExplosionResolver} reads {@code ProductService}). If
 * {@code products} imported {@code StockOnHandRepository} directly, the module graph would have a
 * cycle ({@code products→stock} + {@code stock→products}), which ArchUnit forbids. By declaring
 * this interface in {@code products} and implementing it in {@code stock}, the dependency arrow
 * stays {@code stock→products} only — {@code stock} implements a port that {@code products} owns.
 * No {@code products→stock} import is needed; {@code BomCostRollUpServiceImpl} depends only on
 * this interface.
 *
 * <p>The implementation ({@code StockLeafCostResolver} in {@code com.erp.modules.stock.service})
 * reads {@code StockOnHandRepository.findAvgCost} via a scalar projection (D-10).
 */
public interface LeafCostResolver {

    /**
     * Batch-fetch the average cost of leaf products for a given (company, branch) scope.
     *
     * <p>Returns a map of {@code productId → avg_cost}; missing entries (no established cost)
     * are absent from the map (never null-valued) — callers check containsKey to detect
     * cost-incomplete leaves (BR-BOM-09).
     *
     * @param companyId  tenant company
     * @param branchId   the branch whose avg_cost is read (ADR-0020 per-branch moving average)
     * @param productIds the leaf product ids to resolve
     * @return map of productId to avg_cost; absent = no cost established
     */
    Map<Long, BigDecimal> avgCosts(Long companyId, Long branchId, List<Long> productIds);
}
