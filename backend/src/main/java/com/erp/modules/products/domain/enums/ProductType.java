package com.erp.modules.products.domain.enums;

/**
 * Catalogue type for a product (FR-PROD-03, ADR-0007 D-2).
 * {@code GOODS} is a tangible item; {@code SERVICE} is intangible and non-stockable (BR-PROD-01).
 * Stored as a VARCHAR(20) column with a DB CHECK constraint.
 */
public enum ProductType {
    GOODS,
    SERVICE
}
