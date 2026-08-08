package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One received line on a direct goods receipt (K3, Kilimanjaro 2026-08-08).
 *
 * <p>Unlike {@link GoodsReceiptLineRequest} this line does NOT reference a purchase-order line —
 * there is no prior LPO. It names the product, the unit it was delivered in, the quantity and what
 * was paid for it; the service turns each of these into a line on the auto-raised purchase order
 * and then receives it in full.
 *
 * <p>{@code unitCostAmount} is per {@code unitUid} (i.e. per carton when the delivery is in
 * cartons), exactly like a PO line. Zero is allowed only with a {@code note} (OQ-PURCH-04).
 * Lot / expiry / serial fields are optional and behave exactly as on a normal receipt (V76 soft
 * validation).
 */
public record DirectGoodsReceiptLineRequest(
        String     productUid,
        String     unitUid,
        BigDecimal receivedQty,
        BigDecimal unitCostAmount,
        /** Required when {@code unitCostAmount} is zero (OQ-PURCH-04). */
        String     note,
        // Optional lot/batch + serial tracking (V76) — mirrors GoodsReceiptLineRequest.
        String       lotNumber,
        LocalDate    manufactureDate,
        LocalDate    expiryDate,
        List<String> serialNumbers
) {
    /** Convenience for callers with no lot/serial data. */
    public DirectGoodsReceiptLineRequest(String productUid, String unitUid,
                                         BigDecimal receivedQty, BigDecimal unitCostAmount,
                                         String note) {
        this(productUid, unitUid, receivedQty, unitCostAmount, note, null, null, null, null);
    }
}
