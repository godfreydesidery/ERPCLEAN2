package com.erp.modules.ap.domain.enums;

/** Lifecycle of a bulk AP payment run (ADR-0041 D3). */
public enum PaymentRunStatus {
    /** Run created/selected but not yet posted; no GL or bill relief yet. */
    DRAFT,
    /** Run posted — member payments executed and bills relieved. */
    POSTED
}
