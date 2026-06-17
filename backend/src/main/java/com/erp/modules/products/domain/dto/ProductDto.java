package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.money.MoneyDto;

/**
 * Full response DTO for a Product (ADR-0007 D-12).
 * Carries both {@code id} (JSON string via global Long-as-string config) and {@code uid}.
 * baseUnit fields are enriched from the UnitOfMeasure association (UoM cutover), mirroring
 * how ProductPriceDto carries priceList code/name.
 * vatStatus added in V5 (ADR-0008 D-5a).
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
        boolean lotTracked,
        boolean serialTracked,
        boolean expiryTracked,
        String baseUnitUid,
        String baseUnitCode,
        String baseUnitName,
        MoneyDto cost,
        VatStatus vatStatus,
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
                p.isLotTracked(),
                p.isSerialTracked(),
                p.isExpiryTracked(),
                p.getBaseUnit() != null ? p.getBaseUnit().getUid()  : null,
                p.getBaseUnit() != null ? p.getBaseUnit().getCode() : null,
                p.getBaseUnit() != null ? p.getBaseUnit().getName() : null,
                MoneyDto.from(p.getCost()),
                p.getVatStatus(),
                p.getStatus(),
                p.getVersion(),
                p.getCreatedAt() != null ? p.getCreatedAt().toString() : null,
                p.getCreatedBy(),
                p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null,
                p.getUpdatedBy()
        );
    }
}
