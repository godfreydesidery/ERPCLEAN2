package com.erp.modules.products.domain.enums;

/**
 * Barcode symbology of a {@link com.erp.modules.products.domain.entity.ProductBarcode} (P2-M3).
 * Stored as VARCHAR(20); default OTHER for back-compatibility with existing rows.
 */
public enum BarcodeType {
    EAN,
    UPC,
    CODE128,
    OTHER,
    /** Embedded-weight scale label (type-2 / EAN-13 variant). ADR-0044 D-1a / BR-9. */
    EMBEDDED_WEIGHT,
    /** Embedded-price scale label (type-2 / EAN-13 variant). ADR-0044 D-1a / BR-9. */
    EMBEDDED_PRICE
}
