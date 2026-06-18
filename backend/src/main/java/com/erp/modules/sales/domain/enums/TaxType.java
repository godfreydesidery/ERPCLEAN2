package com.erp.modules.sales.domain.enums;

/**
 * Classification of a tax rate, generalising the master beyond VAT (P2-M3).
 * Stored as VARCHAR(20); default VAT for back-compatibility with existing rows.
 */
public enum TaxType {
    VAT,
    WHT,
    EXCISE,
    OTHER
}
