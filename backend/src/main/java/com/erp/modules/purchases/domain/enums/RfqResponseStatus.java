package com.erp.modules.purchases.domain.enums;

/**
 * Per-supplier RFQ response tracking status (P2 mechanical, GAP-FIX-WORKLIST purchases/RfqSupplier).
 * Stored on rfq_supplier.response_status; nullable.
 */
public enum RfqResponseStatus {
    PENDING,
    RESPONDED,
    DECLINED,
    NO_RESPONSE
}
