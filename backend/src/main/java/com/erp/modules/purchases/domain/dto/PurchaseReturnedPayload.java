package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Outbox payload for {@code PURCHASE.RETURNED} (ADR-0027 D-7 / D-10).
 * Each line carries the product, qty and original receipt cost so the
 * {@link com.erp.modules.stock.events.PurchaseReturnStockHandler} can reverse the
 * moving-average and post DR GRNI / CR INVENTORY.
 */
public record PurchaseReturnedPayload(
        String purchaseReturnUid,
        Long companyId,
        Long branchId,
        BigDecimal totalReturnValue,
        String currency,
        List<ReturnLine> lines
) {

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
