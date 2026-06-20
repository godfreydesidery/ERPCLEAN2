package com.erp.modules.products.domain.enums;

/**
 * What the embedded field in a variable-digit (type-2) barcode encodes (ADR-0044 D-1a).
 * Stored as VARCHAR(10) in {@code barcode_symbology_rules.value_kind}.
 */
public enum SymbologyValueKind {
    /** The embedded field is a weight (e.g. grams, kg scaled by {@code value_decimals}). */
    WEIGHT,
    /** The embedded field is a price (local currency, scaled by {@code value_decimals}). */
    PRICE
}
