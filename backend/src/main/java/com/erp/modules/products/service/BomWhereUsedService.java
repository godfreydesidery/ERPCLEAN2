package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.WhereUsedRowDto;
import com.erp.modules.products.domain.dto.WhereUsedTreeDto;
import java.util.List;

/**
 * Where-used (implosion) service (ADR-0026 D-6, FR-BOM-14/15, OQ-BOM-04).
 *
 * <p>Given a component product, answers which parent BOMs consume it.
 * <ul>
 *   <li>Single-level (FR-BOM-14): direct parents — guaranteed minimum.</li>
 *   <li>Full implosion (FR-BOM-15, OQ-BOM-04): walks up parents-of-parents to finished goods;
 *       best-effort, bounded by max-depth.</li>
 * </ul>
 */
public interface BomWhereUsedService {

    /**
     * Single-level where-used: BOM headers that directly consume the given component (FR-BOM-14).
     *
     * @param componentProductUid uid of the component product
     * @param companyId           tenant scope
     * @return list of BOM headers (with parent product, version, status, qty_per) consuming the component
     */
    List<WhereUsedRowDto> whereUsed(String componentProductUid, Long companyId);

    /**
     * Full implosion (where-used tree): all ancestor parents up to top-level finished goods
     * (FR-BOM-15, OQ-BOM-04 best-effort), bounded by max-depth.
     *
     * @param componentProductUid uid of the component product
     * @param companyId           tenant scope
     * @return implosion tree with path and effective qty-per along each branch
     */
    WhereUsedTreeDto whereUsedTree(String componentProductUid, Long companyId);
}
