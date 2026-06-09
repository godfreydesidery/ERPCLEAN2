package com.erp.modules.ap.domain.enums;

/** Per-line 3-way match result (ADR-0015 D-2c). */
public enum BillMatchStatus {
    MATCHED,
    HELD_PRICE_VARIANCE,
    HELD_QTY_VARIANCE,
    VARIANCE_ACCEPTED
}
