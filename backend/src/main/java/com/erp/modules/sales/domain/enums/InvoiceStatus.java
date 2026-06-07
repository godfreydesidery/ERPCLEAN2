package com.erp.modules.sales.domain.enums;

/**
 * Sales document lifecycle (ADR-0008 D-7).
 * Legal transitions: DRAFT → FINALISED → VOID. No other transitions.
 */
public enum InvoiceStatus {
    DRAFT,
    FINALISED,
    VOID
}
