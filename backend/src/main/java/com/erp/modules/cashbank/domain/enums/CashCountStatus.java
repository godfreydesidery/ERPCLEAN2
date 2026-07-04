package com.erp.modules.cashbank.domain.enums;

/**
 * Lifecycle of an end-of-day cash count (ADR-0050 D-7 PR-A).
 * OPEN (expected derived) -&gt; COUNTED (denominations recorded, variance computed)
 * -&gt; RECONCILED (variance auto-posted to GL + linked cash-book row written).
 */
public enum CashCountStatus {
    OPEN,
    COUNTED,
    RECONCILED
}
