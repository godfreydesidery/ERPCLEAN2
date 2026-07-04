package com.erp.modules.stock.domain.enums;

/**
 * Lifecycle of a van reconciliation worksheet (ADR-0051 D-8).
 *
 * <p>{@code OPEN} → {@code RECONCILED} (record-only: no stock/GL post — ADR-0051 D-8.2), or
 * {@code OPEN} → {@code CANCELLED}. Terminal states do not transition further.
 */
public enum VanReconciliationStatus {
    OPEN,
    RECONCILED,
    CANCELLED
}
