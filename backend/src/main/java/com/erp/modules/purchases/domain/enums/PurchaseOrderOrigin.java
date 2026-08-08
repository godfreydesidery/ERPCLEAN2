package com.erp.modules.purchases.domain.enums;

/**
 * How a purchase order came into existence (V96, K3 — Kilimanjaro 2026-08-08).
 *
 * <p>Persisted as a string in {@code purchase_orders.origin} (VARCHAR(16) NOT NULL DEFAULT
 * {@code 'MANUAL'}, CHECK {@code chk_purchase_order_origin}). Every row that existed before V96 is
 * {@code MANUAL}, and every path that a person can drive — the PO screen, a requisition conversion,
 * a quote award, a drop-ship PO — still produces {@code MANUAL}. Only the direct-receipt service
 * stamps {@code DIRECT_RECEIPT}.
 *
 * <p><b>Why it exists.</b> A direct goods receipt (goods delivered with no prior LPO) auto-raises a
 * backing purchase order so that the whole downstream chain — 3-way match, GRNI clearing, purchase
 * returns, outstanding tracking — keeps working untouched. Those synthesised orders are book-keeping
 * artefacts, not work for a buyer, so the purchase-order list hides them by default; this column is
 * what makes them distinguishable.
 */
public enum PurchaseOrderOrigin {

    /** Raised by a person through the normal requisition / quote / PO flow. The default. */
    MANUAL,

    /**
     * Synthesised by {@code DirectGoodsReceiptService} to anchor a receipt that had no LPO.
     * Hidden from the purchase-order list unless the caller explicitly opts in.
     */
    DIRECT_RECEIPT
}
