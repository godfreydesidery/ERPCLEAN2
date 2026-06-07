package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.platform.common.domain.MasterStatus;

/**
 * Lightweight product DTO for list/search results (NFR-PROD-02).
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
        String baseUnit,
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
                p.getBaseUnit(),
                p.getStatus()
        );
    }
}
