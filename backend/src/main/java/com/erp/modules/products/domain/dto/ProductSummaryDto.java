package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.platform.common.domain.MasterStatus;

/**
 * Lightweight product DTO for list/search results (NFR-PROD-02).
 * Shows baseUnitCode (enriched from UnitOfMeasure) instead of free-text baseUnit.
 */
public record ProductSummaryDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        String name,
        ProductType type,
        boolean sellable,
        boolean stockable,
        String baseUnitCode,
        MasterStatus status
) {

    public static ProductSummaryDto from(Product p) {
        return new ProductSummaryDto(
                p.getId(),
                p.getUid(),
                p.getCompanyId(),
                p.getCode(),
                p.getName(),
                p.getType(),
                p.isSellable(),
                p.isStockable(),
                p.getBaseUnit() != null ? p.getBaseUnit().getCode() : null,
                p.getStatus()
        );
    }
}
