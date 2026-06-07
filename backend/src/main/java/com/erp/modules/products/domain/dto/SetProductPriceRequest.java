package com.erp.modules.products.domain.dto;

import com.erp.platform.common.money.MoneyDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request DTO to set (upsert) a product's price on a named price list (FR-PROD-10/11). */
public record SetProductPriceRequest(
        @NotBlank String priceListUid,
        @NotNull MoneyDto price
) {
}
