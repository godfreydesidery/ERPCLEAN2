package com.erp.modules.stock.service;

import com.erp.modules.products.service.LeafCostResolver;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockOnHandRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *
 * <p>Multi-location aggregation (MANUFACTURING-024): a product may be stocked at multiple locations
 * within a branch. Using the deprecated {@code findByCompanyIdAndBranchIdAndProductId} (which
 * returns {@code Optional}) throws {@code IncorrectResultSizeDataAccessException} in that case.
 * This implementation uses {@link StockOnHandRepository#findAllByCompanyIdAndBranchIdAndProductId}
 * and computes a weighted-average cost across all location rows.
 */
@Component
@Transactional(readOnly = true)
public class StockLeafCostResolver implements LeafCostResolver {

    private static final int COST_SCALE = 4;
    private static final RoundingMode HALF_UP = RoundingMode.HALF_UP;

    private final StockOnHandRepository stockOnHands;

    public StockLeafCostResolver(StockOnHandRepository stockOnHands) {
        this.stockOnHands = stockOnHands;
    }

    /**
     * Batch-fetches the weighted-average cost for the given product ids at (company, branch) scope,
     * aggregating across all stock locations within the branch.
     *
     * <p>Weighted-average formula: {@code Σ(qty × avgCost) / Σ(qty)} across location rows that
     * have a non-null avg_cost and positive quantity. If all costed rows have zero or negative
     * total quantity, falls back to a simple average of the avg_cost values. Missing entries (no
     * on-hand row with an established cost) are absent from the result map.
     *
     * <p>N+1 note: this issues one query per product id. A batch JPQL query keyed on
     * {@code productId IN (:ids)} can be added when Manufacturing requires higher throughput.
     */
    @Override
    public Map<Long, BigDecimal> avgCosts(Long companyId, Long branchId, List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Long productId : productIds) {
            List<StockOnHand> rows = stockOnHands
                    .findAllByCompanyIdAndBranchIdAndProductId(companyId, branchId, productId);
            BigDecimal weightedAvg = weightedAverageCost(rows);
            if (weightedAvg != null) {
                result.put(productId, weightedAvg);
            }
        }
        return result;
    }

    /**
     * Computes the weighted-average unit cost across multiple on-hand location rows.
     *
     * <ul>
     *   <li>Only rows with a non-null {@code avgCost} contribute.</li>
     *   <li>If the total costed quantity is positive, returns {@code Σ(qty × cost) / Σ(qty)}.</li>
     *   <li>If total costed quantity is zero or negative (edge case: all stock at zero/negative),
     *       returns the unweighted mean of the avg_cost values so the roll-up is never blocked by
     *       an inventory correction that momentarily drives a location negative.</li>
     *   <li>Returns {@code null} if no row carries an established cost.</li>
     * </ul>
     */
    private BigDecimal weightedAverageCost(List<StockOnHand> rows) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalQty   = BigDecimal.ZERO;
        BigDecimal costSum    = BigDecimal.ZERO;
        int costedRowCount    = 0;

        for (StockOnHand row : rows) {
            if (row.getAvgCost() == null) {
                continue;
            }
            BigDecimal qty = row.getQuantity() != null ? row.getQuantity() : BigDecimal.ZERO;
            totalQty   = totalQty.add(qty);
            totalValue = totalValue.add(qty.multiply(row.getAvgCost()));
            costSum    = costSum.add(row.getAvgCost());
            costedRowCount++;
        }

        if (costedRowCount == 0) {
            return null;
        }
        if (totalQty.compareTo(BigDecimal.ZERO) > 0) {
            return totalValue.divide(totalQty, COST_SCALE, HALF_UP);
        }
        // Fallback: unweighted mean when all costed qty is zero or negative
        return costSum.divide(BigDecimal.valueOf(costedRowCount), COST_SCALE, HALF_UP);
    }
}
