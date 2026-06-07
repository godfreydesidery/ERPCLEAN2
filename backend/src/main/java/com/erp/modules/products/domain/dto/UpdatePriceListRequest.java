package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.NotBlank;

/** Request DTO to update a PriceList's name (code is immutable once set). */
public record UpdatePriceListRequest(
        @NotBlank String name
) {
}
