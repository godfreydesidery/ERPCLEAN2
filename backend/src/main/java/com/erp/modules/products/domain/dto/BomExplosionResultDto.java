package com.erp.modules.products.domain.dto;

import java.util.List;

/**
 * Full explosion result — indented tree + flattened leaf summary + optional cost roll-up
 * (ADR-0026 D-6, FR-BOM-12/13/16).
 *
 * @param bomUid        the BOM version used (resolved or explicit)
 * @param parentProductUid  the product exploded
 * @param outputQty     the requested output quantity
 * @param tree          indented explosion tree (ordered by level then line_no)
 * @param leaves        flattened net requirement per BUY/raw leaf
 * @param costRollUp    derived standard cost (null if branchUid not supplied or withCost=false)
 */
public record BomExplosionResultDto(
        String bomUid,
        String parentProductUid,
        java.math.BigDecimal outputQty,
        List<BomExplosionNodeDto> tree,
        List<BomExplosionLeafDto> leaves,
        BomCostRollUpDto costRollUp
) {
}
