package com.erp.modules.sales.domain.enums;

/**
 * Lifecycle status for a sales return / RMA (ADR-0021 D-2, Stage 2).
 *
 * <p>In v1 a return is created directly in CONFIRMED status (the stock-in + COGS reversal +
 * credit note happen atomically on create). DRAFT is reserved in the enum for a future
 * approval-workflow extension but is not used by any v1 code path.
 */
public enum SalesReturnStatus {
    DRAFT,
    CONFIRMED
}
