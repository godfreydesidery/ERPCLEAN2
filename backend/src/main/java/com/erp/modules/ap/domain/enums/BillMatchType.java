package com.erp.modules.ap.domain.enums;

/**
 * The match discipline applied to a bill line (ADR-0041 D7).
 * TWO_WAY = bill ↔ PO (no goods receipt); THREE_WAY = bill ↔ PO ↔ GR.
 */
public enum BillMatchType {
    TWO_WAY,
    THREE_WAY
}
