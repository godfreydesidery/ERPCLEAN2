package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.BomExplosionLeafDto;
import com.erp.modules.products.domain.dto.BomExplosionResultDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Multi-level BOM explosion service (ADR-0026 D-6, FR-BOM-12/13).
 *
 * <p>Resolves the applicable BOM version for a parent product and recursively expands it to:
 * (1) an indented tree ({@code BomExplosionNodeDto}) and (2) a flattened leaf summary
 * ({@code BomExplosionLeafDto}) — the net requirement of each BUY/raw component.
 *
 * <p>This service is the single explosion implementation (NFR-BOM-04): the shipped
 * {@link com.erp.modules.stock.service.RecipeExplosionResolver} delegates to this when an ACTIVE
 * BOM exists (D-7), keeping its single-level {@code product_components} behaviour as the default
 * the sale path uses.
 */
public interface BomExplosionService {

    /**
     * Full explosion result with optional cost roll-up.
     *
     * @param parentProductUid  uid of the product to explode (resolved to its ACTIVE BOM)
     * @param bomUid            if non-null, use this specific BOM version (any status — historical)
     * @param outputQty         the output quantity to scale the explosion to
     * @param multiLevel        true = recurse through MAKE components; false = one level (sale path)
     * @param asOfDate          if non-null, resolve the version effective on this date
     * @param branchUid         if non-null, include cost roll-up from avg_cost at this branch
     * @param withCost          if true and branchUid is given, compute the cost roll-up
     * @return explosion result
     */
    BomExplosionResultDto explode(String parentProductUid,
                                  String bomUid,
                                  BigDecimal outputQty,
                                  boolean multiLevel,
                                  LocalDate asOfDate,
                                  String branchUid,
                                  boolean withCost);

    /**
     * Flattened leaf-only explosion — the shape the {@code RecipeExplosionResolver} upgrade (D-7)
     * needs: a list of (productId, totalQuantity) pairs for SALE_ISSUE movements.
     *
     * @param parentProductUid uid of the composed product
     * @param outputQty        the sold quantity
     * @param multiLevel       true = full multi-level; false = immediate components only (sale path)
     * @return leaf summary; empty if the product has no ACTIVE BOM
     */
    List<BomExplosionLeafDto> explodeToLeaves(String parentProductUid,
                                              BigDecimal outputQty,
                                              boolean multiLevel);

    /**
     * Flattened leaf-only explosion of one SPECIFIC BOM version, whatever its status.
     *
     * <p>Use this whenever the caller already knows which version it means — a cost roll-up asked
     * for a named {@code bomUid}, or a work order exploding the version it pinned at release.
     * Going through {@link #explodeToLeaves(String, BigDecimal, boolean)} instead would resolve the
     * parent product and re-select the ACTIVE version, silently costing or consuming a different
     * recipe than the one requested.
     *
     * <p>Nested MAKE components still recurse into their children's ACTIVE BOMs: only the top
     * level is pinned, because only the top level was named.
     *
     * @param bomUid     uid of the BOM version to explode (DRAFT, ACTIVE or ARCHIVED)
     * @param outputQty  the output quantity to scale the explosion to
     * @param multiLevel true = recurse through MAKE components; false = one level
     * @return leaf summary; empty when that version has no component lines
     */
    List<BomExplosionLeafDto> explodeBomToLeaves(String bomUid,
                                                 BigDecimal outputQty,
                                                 boolean multiLevel);

    /**
     * Returns true if the product has an ACTIVE BOM (used by
     * {@code RecipeExplosionResolver.shouldExplodeAtIssue} and make/buy defaulting — D-7, D-3).
     */
    boolean hasActiveBom(String parentProductUid);
}
