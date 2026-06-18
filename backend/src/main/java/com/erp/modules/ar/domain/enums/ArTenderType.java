package com.erp.modules.ar.domain.enums;

/**
 * Tender type of an AR receipt (ADR-0041 D3).
 *
 * <p>Replaces the historical free-string tender set with a typed enum. The persisted
 * {@code ar_receipts.tender_type} column also still admits the legacy {@code BANK_TRANSFER}
 * value (back-compat) via the widened DB CHECK, but new code should use this enum.
 */
public enum ArTenderType {
    CASH,
    CHEQUE,
    MOBILE_MONEY,
    CARD
}
