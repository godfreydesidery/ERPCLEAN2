package com.erp.modules.parties.domain.enums;

/**
 * Functional role of a party address (ADR-0040 D-3).
 * Stored as VARCHAR(20) — DB CHECK constraint mirrors these values.
 */
public enum AddressRole {
    BILL_TO,
    SHIP_TO,
    GENERAL
}
