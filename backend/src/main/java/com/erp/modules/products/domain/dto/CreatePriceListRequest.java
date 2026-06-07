package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to create a new PriceList.
 * Carries {@code companyUid} (String) per ADR-0007 D-12.
 */
public record CreatePriceListRequest(
        @NotBlank String companyUid,
        @NotBlank String code,
        @NotBlank String name
) {
}
