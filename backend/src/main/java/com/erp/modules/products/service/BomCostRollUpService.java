package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.BomCostRollUpDto;
import java.math.BigDecimal;

/**
 * Derived standard-cost roll-up service (ADR-0026 D-8, FR-BOM-16, BR-BOM-09).
 *
 * <p>Reads the explosion leaf summary and multiplies each leaf's rolled-up quantity by the branch's
 * moving-average {@code avg_cost} (ADR-0020). Writes nothing, posts no GL, emits no event.
 * This is a {@code @Transactional(readOnly = true)} report.
 *
 * <p>Leaves with no established avg_cost are flagged as incomplete (BR-BOM-09 — never silently zero).
 */
public interface BomCostRollUpService {

    /**
     * Compute the standard-cost roll-up for a parent product (or specific BOM version) at a branch.
     *
     * <p>A supplied {@code bomUid} is authoritative: that exact version is costed whatever its
     * status, and the returned {@code bomUid}/{@code parentProductUid} describe it. Supplying only
     * {@code parentProductUid} costs the product's ACTIVE version. A version with no component
     * lines is refused rather than substituted or reported as zero.
     *
     * @param parentProductUid uid of the parent product; when {@code bomUid} is also given the two
     *                         must describe the same product
     * @param bomUid           specific BOM version uid; wins over {@code parentProductUid}
     * @param branchUid        the branch whose avg_cost is read (ADR-0020 per-branch)
     * @param outputQty        output quantity to scale the explosion to
     * @return cost roll-up with partial-cost flag and incomplete-leaf list (BR-BOM-09)
     */
    BomCostRollUpDto rollUp(String parentProductUid, String bomUid, String branchUid, BigDecimal outputQty);
}
