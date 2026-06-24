package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One line within a CreateGoodsReceiptRequest: specifies which PO line to draw down,
 * how much to receive, and optional lot/serial tracking data (V76).
 *
 * <p>purchaseOrderLineUid is resolved by the service (company-scoped, F16 child-by-parent).
 * receivedQty is in the same unit as the PO line's unit_id.
 * Over-receipt (receivedQty > outstanding) is rejected by the service (BR-PURCH-10, ADR-0011 D-3).
 *
 * <p>Lot / serial / expiry are ALL optional (soft validation). The service captures whatever
 * is provided: trims strings, skips blanks, deduplicates serial numbers. A receipt with no
 * lot/serial data is fully valid (BR-V76-01).
 */
public record GoodsReceiptLineRequest(
        String     purchaseOrderLineUid,
        BigDecimal receivedQty,
        // V76 — optional lot/batch tracking fields
        String     lotNumber,
        LocalDate  manufactureDate,
        LocalDate  expiryDate,
        List<String> serialNumbers
) {
    /**
     * Back-compat compact constructor: callers that only supply uid + qty still compile.
     * Equivalent to {@code new GoodsReceiptLineRequest(uid, qty, null, null, null, null)}.
     */
    public GoodsReceiptLineRequest(String purchaseOrderLineUid, BigDecimal receivedQty) {
        this(purchaseOrderLineUid, receivedQty, null, null, null, null);
    }
}
