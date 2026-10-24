package com.erp.modules.sales.domain.enums;

/** What a prep-station slip tells the kitchen or bar (ADR-0065 D-6, D-9). */
public enum PrepSlipKind {
    /** Prepare these items. */
    FIRE,
    /** Stop: these items were cancelled after being sent. */
    VOID
}
