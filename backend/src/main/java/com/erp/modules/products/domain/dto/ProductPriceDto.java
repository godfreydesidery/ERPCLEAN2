package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.platform.common.money.MoneyDto;
import java.time.LocalDate;

/** Response DTO for a product price entry (FR-PROD-10/11). */
public record ProductPriceDto(
        Long id,
        Long productId,
        Long priceListId,
        String priceListUid,
        String priceListCode,
        String priceListName,
        Long companyId,
        MoneyDto price,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {

    public static ProductPriceDto from(ProductPrice pp) {
        return new ProductPriceDto(
                pp.getId(),
                pp.getProduct().getId(),
                pp.getPriceList().getId(),
                pp.getPriceList().getUid(),
                pp.getPriceList().getCode(),
                pp.getPriceList().getName(),
                pp.getCompanyId(),
                MoneyDto.from(pp.getPrice()),
                pp.getEffectiveFrom(),
                pp.getEffectiveTo()
        );
    }
}
