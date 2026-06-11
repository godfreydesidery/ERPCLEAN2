package com.erp.modules.products.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Full implosion (where-used tree) result (ADR-0026 D-6, FR-BOM-15, OQ-BOM-04).
 *
 * <p>Walking up from the queried component through all ancestor parents to top-level finished goods.
 * Single-level where-used (FR-BOM-14) is the guaranteed minimum; full implosion is best-effort.
 */
public record WhereUsedTreeDto(
        String componentProductUid,
        String componentProductName,
        List<WhereUsedNodeDto> parents
) {

    /**
     * One ancestor level in the implosion tree.
     */
    public record WhereUsedNodeDto(
            String bomUid,
            Integer bomVersionNo,
            String parentProductUid,
            String parentProductCode,
            String parentProductName,
            BigDecimal qtyPer,
            List<WhereUsedNodeDto> parents
    ) {
    }
}
