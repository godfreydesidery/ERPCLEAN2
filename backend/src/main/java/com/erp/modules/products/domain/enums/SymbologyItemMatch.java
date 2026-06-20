package com.erp.modules.products.domain.enums;

/**
 * How the extracted item-code substring is resolved to a product (ADR-0044 D-1a).
 * Stored as VARCHAR(16) in {@code barcode_symbology_rules.item_match}.
 */
public enum SymbologyItemMatch {
    /** The item-code substring is looked up against {@code product_barcodes.barcode}. */
    BARCODE,
    /** The item-code substring is looked up against {@code products.code}. */
    PRODUCT_CODE
}
