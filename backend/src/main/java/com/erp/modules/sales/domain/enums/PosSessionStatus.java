package com.erp.modules.sales.domain.enums;

/**
 * Lifecycle of a POS session (ADR-0029 D-5).
 * OPEN → CLOSED → RECONCILED.
 */
public enum PosSessionStatus {
    OPEN,
    CLOSED,
    RECONCILED
}
