package com.erp.modules.sales.domain.enums;

/**
 * Invoice origin discriminator (ADR-0021 D-8).
 * DIRECT = walk-in invoice (issues stock on finalise).
 * SALES_ORDER = invoice raised from a delivery (revenue-only; stock already issued at delivery).
 */
public enum DocumentOrigin {
    DIRECT,
    SALES_ORDER
}
