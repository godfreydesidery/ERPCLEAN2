package com.erp.modules.products.domain.enums;

/**
 * The scope a promotion applies to (ADR-0029 D-6, FR-SD-11).
 * PRODUCT = a specific product; CATEGORY = all products in a category; ALL = every product.
 */
public enum PromotionTarget {
    PRODUCT,
    CATEGORY,
    ALL
}
