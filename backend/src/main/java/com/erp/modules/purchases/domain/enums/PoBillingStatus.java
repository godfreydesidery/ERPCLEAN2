package com.erp.modules.purchases.domain.enums;

/**
 * PO header billing roll-up (P2 mechanical, GAP-FIX-WORKLIST purchases/PurchaseOrder).
 * Stored on purchase_orders.billing_status; nullable (no maintenance logic in P2).
 */
public enum PoBillingStatus {
    NOT_BILLED,
    PARTIALLY_BILLED,
    FULLY_BILLED
}
