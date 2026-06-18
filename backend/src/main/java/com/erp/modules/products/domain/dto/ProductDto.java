package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.money.MoneyDto;
import java.math.BigDecimal;

/**
 * Full response DTO for a Product (ADR-0007 D-12).
 * Carries both {@code id} (JSON string via global Long-as-string config) and {@code uid}.
 * baseUnit fields are enriched from the UnitOfMeasure association (UoM cutover), mirroring
 * how ProductPriceDto carries priceList code/name.
 * vatStatus added in V5 (ADR-0008 D-5a).
 * D-10 planning/sourcing fields added in ADR-0040.
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
        // P2-M3 descriptive / logistics attributes
        String brand,
        String manufacturer,
        BigDecimal weight,
        BigDecimal volume,
        String dimensions,
        String hsCode,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy,
        // D-10 planning + sourcing
        BigDecimal reorderLevel,
        BigDecimal reorderQty,
        BigDecimal safetyStock,
        BigDecimal minStock,
        BigDecimal maxStock,
        Integer leadTimeDays,
        boolean purchasable,
        Long preferredSupplierId
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
                // P2-M3
                p.getBrand(),
                p.getManufacturer(),
                p.getWeight(),
                p.getVolume(),
                p.getDimensions(),
                p.getHsCode(),
                p.getVersion(),
                p.getCreatedAt() != null ? p.getCreatedAt().toString() : null,
                p.getCreatedBy(),
                p.getUpdatedAt() != null ? p.getUpdatedAt().toString() : null,
                p.getUpdatedBy(),
                // D-10
                p.getReorderLevel(),
                p.getReorderQty(),
                p.getSafetyStock(),
                p.getMinStock(),
                p.getMaxStock(),
                p.getLeadTimeDays(),
                p.isPurchasable(),
                p.getPreferredSupplierId()
        );
    }
}
