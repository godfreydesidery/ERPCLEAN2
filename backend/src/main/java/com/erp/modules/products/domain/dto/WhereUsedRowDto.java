package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.enums.BomStatus;
import com.erp.modules.products.domain.enums.ComponentSourcing;
import java.math.BigDecimal;

/**
 * One row in a single-level where-used result (ADR-0026 D-6, FR-BOM-14).
 *
 * <p>Represents a BOM header that directly consumes the queried component.
 *
 * @param bomUid           the BOM version uid
 * @param bomVersionNo     version number
 * @param bomStatus        DRAFT / ACTIVE / ARCHIVED
 * @param parentProductUid uid of the product this BOM produces
 * @param parentProductCode
 * @param parentProductName
 * @param qtyPer           qty_per on the component line (raw, before scrap/yield)
 * @param sourcing         MAKE or BUY
 */
public record WhereUsedRowDto(
        String bomUid,
        Integer bomVersionNo,
        BomStatus bomStatus,
        String parentProductUid,
        String parentProductCode,
        String parentProductName,
        BigDecimal qtyPer,
        ComponentSourcing sourcing
) {
}
