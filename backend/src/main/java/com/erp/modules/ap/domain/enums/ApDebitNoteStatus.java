package com.erp.modules.ap.domain.enums;

/** Application state of an AP debit note (ADR-0041 D3 — mirror of ArCreditNoteStatus). */
public enum ApDebitNoteStatus {
    /** Full amount unapplied — no allocations yet. */
    UNAPPLIED,
    /** Partially applied — some allocations exist but unapplied_amount > 0. */
    PARTIAL,
    /** Fully applied — unapplied_amount == 0. */
    APPLIED
}
