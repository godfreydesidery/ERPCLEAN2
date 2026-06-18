package com.erp.modules.sales.domain.enums;

/**
 * Payment tender type (ADR-0008 D-8, FR-SALES-17).
 *
 * <p>v1 admits CASH and MOBILE_MONEY; ADR-0041 D3 adds CHEQUE and CARD so the immediate-payment
 * path can capture structured instrument links (cheque_id / card_ref) on sales_invoice_payments.
 * Only CASH accepts over-tender change (BR-SALES-07) — the paid-in-full invariant is unchanged.
 */
public enum TenderType {
    CASH,
    MOBILE_MONEY,
    CHEQUE,
    CARD
}
