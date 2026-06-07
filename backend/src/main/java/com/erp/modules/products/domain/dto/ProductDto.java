package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.money.MoneyDto;

/**
 * Full response DTO for a Product (ADR-0007 D-12).
 * Carries both {@code id} (JSON string via global Long-as-string config) and {@code uid}.
 * Money fields follow ADR-0005 D-7 via the promoted {@code platform.common.money.MoneyDto}.
 */
public record ProductDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        String name,
        String description,
        ProductType type,
        boolean sellable,
        boolean stockable,
        String baseUnit,
        MoneyDto cost,
        MasterStatus status,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {

    public static ProductDto from(Product p) {
        return new ProductDto(
                p.getId(),
                p.getUid(),
                p.getCompanyId(),
                p.getCode(),
                p.getName(),
                p.getDescription(),
                p.getType(),
                p.isSellable(),
                p.isStockable(),
                p.getBaseUnit(),
                MoneyDto.from(p.getCost()),
                p.getStatus(),
                p.getVersion(),
                p.getCreatedAt() != null ? p.getCreatedAt().toString() : null,
                p.getCreatedBy(),
                p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null,
                p.getUpdatedBy()
        );
    }
}
