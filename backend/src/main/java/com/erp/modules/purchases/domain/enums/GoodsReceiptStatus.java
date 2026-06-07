package com.erp.modules.purchases.domain.enums;

/**
 * Goods Receipt lifecycle status (ADR-0011 D-6).
 *
 * <p>Legal transitions (service-enforced):
 * <ul>
 *   <li>DRAFT → RECEIVED (receive; assigns GRN-####; emits STOCK.RECEIVED)</li>
 *   <li>RECEIVED → VOID (void; emits STOCK.RECEIPT.VOIDED)</li>
 * </ul>
 */
public enum GoodsReceiptStatus {
    DRAFT,
    RECEIVED,
    VOID
}
