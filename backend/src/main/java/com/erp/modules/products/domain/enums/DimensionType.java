package com.erp.modules.products.domain.enums;

/**
 * Physical dimension family of a unit of measure (P2 D5, ADR-0041 D5).
 *
 * <p>Used to group units (e.g. KG/GRAM are WEIGHT; LITRE/ML are VOLUME) for conversion semantics
 * and validation. Defaults to {@link #COUNT}.
 */
public enum DimensionType {
    COUNT,
    WEIGHT,
    VOLUME,
    LENGTH,
    TIME
}
