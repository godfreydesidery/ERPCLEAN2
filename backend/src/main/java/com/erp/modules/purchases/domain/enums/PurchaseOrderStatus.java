package com.erp.modules.purchases.domain.enums;

/**
 * Purchase Order lifecycle status (ADR-0011 D-6).
 *
 * <p>Legal transitions (service-enforced):
 * <ul>
 *   <li>DRAFT → ORDERED (place order; assigns PO-####)</li>
 *   <li>ORDERED → PARTIALLY_RECEIVED → RECEIVED (driven by GR receives)</li>
 *   <li>{ORDERED, PARTIALLY_RECEIVED, RECEIVED} → CLOSED (explicit operator close)</li>
 *   <li>{DRAFT, ORDERED, PARTIALLY_RECEIVED} → VOID</li>
 * </ul>
 */
public enum PurchaseOrderStatus {
    DRAFT,
    ORDERED,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CLOSED,
    VOID
}
