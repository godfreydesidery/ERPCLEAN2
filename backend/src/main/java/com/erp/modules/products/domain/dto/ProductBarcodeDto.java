package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.ProductBarcode;

/** Response DTO for a product barcode (FR-PROD-08). */
public record ProductBarcodeDto(
        Long id,
        String uid,
        Long productId,
        Long companyId,
        String barcode,
        boolean primary
) {

    public static ProductBarcodeDto from(ProductBarcode b) {
        return new ProductBarcodeDto(
                b.getId(),
                b.getUid(),
                b.getProduct().getId(),
                b.getCompanyId(),
                b.getBarcode(),
                b.isPrimary()
        );
    }
}
