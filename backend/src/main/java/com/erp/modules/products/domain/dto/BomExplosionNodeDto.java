package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.ComponentSourcing;
import java.math.BigDecimal;
import java.util.List;

/**
 * One node in the indented explosion tree (ADR-0026 D-6, FR-BOM-12/13).
 *
 * @param level         depth (0 = direct component of the root, 1 = its child, etc.)
 * @param componentProductId   internal product id
 * @param componentProductUid  product uid
 * @param componentProductCode product code snapshot
 * @param componentProductName product name snapshot
 * @param sourcing      MAKE or BUY (from the component line)
 * @param qtyAtLevel    the quantity at this specific position in the tree (scrap/yield applied)
 * @param isLeaf        true when sourcing=BUY or multiLevel=false or no child ACTIVE BOM found
 * @param children      child nodes (empty for leaves)
 */
public record BomExplosionNodeDto(
        int level,
        Long componentProductId,
        String componentProductUid,
        String componentProductCode,
        String componentProductName,
        ComponentSourcing sourcing,
        BigDecimal qtyAtLevel,
        boolean isLeaf,
        List<BomExplosionNodeDto> children
) {
}
