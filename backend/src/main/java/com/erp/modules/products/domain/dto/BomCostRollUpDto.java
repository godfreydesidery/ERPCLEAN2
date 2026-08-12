package com.erp.modules.products.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Derived standard-cost roll-up result (ADR-0026 D-8, FR-BOM-16, BR-BOM-09).
 *
 * <p>The roll-up is computed on demand from leaf {@code avg_cost} (ADR-0020 moving average),
 * branch-scoped. Writes nothing, posts no GL, emits no event.
 *
 * @param parentProductUid  the product exploded
 * @param bomUid            the BOM version whose components produced {@code standardCostAmount};
 *                          null only when the request named no version and the product's ACTIVE
 *                          version was used. When non-null it is the version actually costed —
 *                          callers rely on this to detect a wrong version, so it must never be a
 *                          bare echo of the request
 * @param branchUid         the branch whose avg_cost was read
 * @param outputQty         the output quantity the explosion was computed for
 * @param standardCostAmount  Σ (leaf totalQuantity × leaf avgCost) for leaves with a cost; null if
 *                            ALL leaves are incomplete
 * @param currency          base currency (ADR-0005)
 * @param complete          true iff every leaf has a non-null avg_cost (BR-BOM-09)
 * @param incompleteLeaves  leaves with no established avg_cost (flagged, never silently zero)
 */
public record BomCostRollUpDto(
        String parentProductUid,
        String bomUid,
        String branchUid,
        BigDecimal outputQty,
        BigDecimal standardCostAmount,
        String currency,
        boolean complete,
        List<IncompleteLeafDto> incompleteLeaves
) {

    /**
     * A leaf component that has no established avg_cost (BR-BOM-09).
     */
    public record IncompleteLeafDto(
            String uid,
            String name
    ) {
    }
}
