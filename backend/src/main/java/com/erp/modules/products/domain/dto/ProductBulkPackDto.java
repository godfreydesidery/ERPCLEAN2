package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.ProductBulkPack;
import java.math.BigDecimal;

/**
 * Response DTO for a product bulk pack unit (FR-PROD-06).
 * unitUid/unitCode/unitName are enriched from the UnitOfMeasure association (UoM cutover).
 */
public record ProductBulkPackDto(
        Long id,
        String uid,
        Long productId,
        String unitUid,
        String unitCode,
        String unitName,
        BigDecimal factorToBase,
        String barcode,
        boolean purchaseDefault,
        boolean saleDefault
) {

    public static ProductBulkPackDto from(ProductBulkPack bp) {
        return new ProductBulkPackDto(
                bp.getId(),
                bp.getUid(),
                bp.getProduct().getId(),
                bp.getUnit() != null ? bp.getUnit().getUid()  : null,
                bp.getUnit() != null ? bp.getUnit().getCode() : null,
                bp.getUnit() != null ? bp.getUnit().getName() : null,
                bp.getFactorToBase(),
                bp.getBarcode(),
                bp.isPurchaseDefault(),
                bp.isSaleDefault()
        );
    }
}
