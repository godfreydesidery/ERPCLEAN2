package com.erp.modules.products.domain.enums;

/**
 * Barcode symbology of a {@link com.erp.modules.products.domain.entity.ProductBarcode} (P2-M3).
 * Stored as VARCHAR(20); default OTHER for back-compatibility with existing rows.
 */
public enum BarcodeType {
    EAN,
    UPC,
    CODE128,
    OTHER
}
