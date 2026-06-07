package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.ProductBulkPack;
import java.math.BigDecimal;

/** Response DTO for a product bulk pack unit (FR-PROD-06). */
public record ProductBulkPackDto(
        Long id,
        String uid,
        Long productId,
        String name,
        BigDecimal factorToBase
) {

    public static ProductBulkPackDto from(ProductBulkPack bp) {
        return new ProductBulkPackDto(
                bp.getId(),
                bp.getUid(),
                bp.getProduct().getId(),
                bp.getName(),
                bp.getFactorToBase()
        );
    }
}
