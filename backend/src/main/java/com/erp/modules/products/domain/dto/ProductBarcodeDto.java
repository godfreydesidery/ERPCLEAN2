package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.ProductBarcode;
import com.erp.modules.products.domain.enums.BarcodeType;

/** Response DTO for a product barcode (FR-PROD-08). */
public record ProductBarcodeDto(
        Long id,
        String uid,
        Long productId,
        Long companyId,
        String barcode,
        BarcodeType barcodeType,
        Long uomId,
        boolean primary
) {

    public static ProductBarcodeDto from(ProductBarcode b) {
        return new ProductBarcodeDto(
                b.getId(),
                b.getUid(),
                b.getProduct().getId(),
                b.getCompanyId(),
                b.getBarcode(),
                b.getBarcodeType(),
                b.getUomId(),
                b.isPrimary()
        );
    }
}
