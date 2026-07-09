package com.erp.modules.products.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to add a barcode to a product (FR-PROD-08).
 *
 * @param unitUid optional unit this barcode addresses — the product's base unit or a configured
 *                bulk-pack unit. Lets a scan resolve to a specific pack size (e.g. the 1kg-bag vs
 *                2kg-bag barcode); null means the barcode addresses the product generally.
 */
public record AddBarcodeRequest(
        @NotBlank String barcode,
        boolean primary,
        String unitUid
) {
    /** Back-compatible 2-arg form (no pack unit) for callers predating per-pack barcodes. */
    public AddBarcodeRequest(String barcode, boolean primary) {
        this(barcode, primary, null);
    }
}
