package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.platform.common.money.MoneyDto;
import java.time.LocalDate;

/**
 * Response DTO for a product price entry (FR-PROD-10/11).
 * {@code unitUid}/{@code unitCode}/{@code unitName} are null for the base-unit price row
 * (ADR-0048 D-1); set for a per-unit (pack-specific) price override.
 */
public record ProductPriceDto(
        Long id,
        Long productId,
        Long priceListId,
        String priceListUid,
        String priceListCode,
        String priceListName,
        Long companyId,
        String unitUid,
        String unitCode,
        String unitName,
        MoneyDto price,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {

    public static ProductPriceDto from(ProductPrice pp) {
        var unit = pp.getUnit();
        return new ProductPriceDto(
                pp.getId(),
                pp.getProduct().getId(),
                pp.getPriceList().getId(),
                pp.getPriceList().getUid(),
                pp.getPriceList().getCode(),
                pp.getPriceList().getName(),
                pp.getCompanyId(),
                unit != null ? unit.getUid() : null,
                unit != null ? unit.getCode() : null,
                unit != null ? unit.getName() : null,
                MoneyDto.from(pp.getPrice()),
                pp.getEffectiveFrom(),
                pp.getEffectiveTo()
        );
    }
}
