package com.erp.modules.sales.domain.enums;

/**
 * Payment tender type (ADR-0008 D-8, FR-SALES-17).
 * v1 admits CASH and MOBILE_MONEY; CARD and CREDIT are reserved for future channels.
 */
public enum TenderType {
    CASH,
    MOBILE_MONEY
    // CARD,   // reserved — deferred (FR-SALES-17)
    // CREDIT  // reserved — deferred (FR-SALES-20)
}
