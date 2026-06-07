package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.NotBlank;

/** Request DTO to add a barcode to a product (FR-PROD-08). */
public record AddBarcodeRequest(
        @NotBlank String barcode,
        boolean primary
) {
}
