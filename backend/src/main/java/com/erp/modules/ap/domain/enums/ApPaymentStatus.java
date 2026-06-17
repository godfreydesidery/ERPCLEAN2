package com.erp.modules.ap.domain.enums;

/** Allocation state of an AP payment (ADR-0040 D-9 — mirror of ArReceiptStatus). */
public enum ApPaymentStatus {
    /** Payment not yet applied to any bill (full amount is on-account). */
    UNALLOCATED,
    /** Payment partially applied — some unallocated remainder remains. */
    PARTIAL,
    /** Payment fully applied — unallocated_amount == 0. */
    ALLOCATED
}
