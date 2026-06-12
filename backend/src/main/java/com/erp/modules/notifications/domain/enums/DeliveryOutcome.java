package com.erp.modules.notifications.domain.enums;

/**
 * Outcome of a delivery attempt (ADR-0024 D-3).
 */
public enum DeliveryOutcome {
    PENDING,
    SENT,
    FAILED,
    SUPPRESSED
}
