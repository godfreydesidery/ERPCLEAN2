package com.erp.modules.ar.domain.enums;

/** Application state of an AR credit note (ADR-0040 D-6). */
public enum ArCreditNoteStatus {
    /** Full amount unapplied — no allocations yet. */
    UNAPPLIED,
    /** Partially applied — some allocations exist but unapplied_amount > 0. */
    PARTIAL,
    /** Fully applied — unapplied_amount == 0. */
    APPLIED
}
