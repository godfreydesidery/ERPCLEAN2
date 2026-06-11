package com.erp.modules.products.domain.dto;

import java.math.BigDecimal;

/**
 * Flattened leaf summary entry — the net requirement of one BUY/raw component across all
 * branches of the explosion tree (ADR-0026 D-6, FR-BOM-13).
 *
 * @param componentProductId   internal product id
 * @param componentProductUid  product uid
 * @param componentProductCode product code
 * @param componentProductName product name
 * @param totalQuantity        Σ effective quantity across all tree branches (6 dp, HALF_UP)
 */
public record BomExplosionLeafDto(
        Long componentProductId,
        String componentProductUid,
        String componentProductCode,
        String componentProductName,
        BigDecimal totalQuantity
) {
}
