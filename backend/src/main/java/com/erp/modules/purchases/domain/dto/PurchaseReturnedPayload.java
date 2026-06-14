package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Outbox payload for {@code PURCHASE.RETURNED} (ADR-0027 D-7 / D-10).
 * Each line carries the product, qty and original receipt cost so the
 * {@link com.erp.modules.stock.events.PurchaseReturnStockHandler} can reverse the
 * moving-average and post DR GRNI / CR INVENTORY.
 *
 * <p>{@code billed} — {@code true} if the GR's goods bill was already matched before the return
 * (GRNI cleared to AP), {@code false} when the bill has not yet arrived.
 * When {@code true} the GRNI re-open is technically a re-accrual; the AP debit note (raised
 * synchronously in the confirm TX) reduces the payable. The stock handler logs a warn when
 * billed=true so that GL reconciliation can verify the GRNI account remains in balance after the
 * subsequent freight-bill match. (ADR-0027 D-7 OQ-RETURN-GL).
 */
public record PurchaseReturnedPayload(
        String purchaseReturnUid,
        Long companyId,
        Long branchId,
        BigDecimal totalReturnValue,
        String currency,
        /**
         * True if the receipt has a matched supplier bill (GRNI already cleared to AP).
         * False (conservative default) means the standard DR GRNI / CR INVENTORY path applies.
         */
        boolean billed,
        List<ReturnLine> lines,
        /** Human-readable return number (e.g. PRN-0001) — used in GL journal memos (FOLLOW-001). */
        String returnNumber
) {
    /** Back-compat: pre-FOLLOW-001 callers that do not supply returnNumber. */
    public PurchaseReturnedPayload(String purchaseReturnUid, Long companyId, Long branchId,
                                   BigDecimal totalReturnValue, String currency, boolean billed,
                                   List<ReturnLine> lines) {
        this(purchaseReturnUid, companyId, branchId, totalReturnValue, currency, billed, lines, null);
    }

    public record ReturnLine(
            Long goodsReceiptLineId,
            String goodsReceiptLineUid,
            Long productId,
            BigDecimal returnedQtyInBase,
            /** Original receipt cost per unit (for reverseReceipt arg). */
            BigDecimal unitCostAmount,
            /** lineValueAmount = returnedQtyInBase × unitCostAmount (pre-computed). */
            BigDecimal lineValue
    ) {}
}
