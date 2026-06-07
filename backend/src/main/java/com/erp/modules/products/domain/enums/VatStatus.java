package com.erp.modules.products.domain.enums;

/**
 * VAT classification of a product (FR-SALES-10, ADR-0008 D-5a).
 * Lives in products.domain.enums because it is a product attribute;
 * Sales re-uses it — no duplicate.
 */
public enum VatStatus {
    /** Standard-rated supply — VAT applies at the maintained company rate (e.g. 18% TZ). */
    STANDARD,
    /** Zero-rated taxable supply — 0% VAT; still a taxable supply. */
    ZERO_RATED,
    /** Exempt supply — outside the VAT net; no VAT computed. */
    EXEMPT
}
