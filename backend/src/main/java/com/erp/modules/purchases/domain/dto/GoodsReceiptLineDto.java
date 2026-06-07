package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.GoodsReceiptLine;
import java.math.BigDecimal;

/**
 * Read-only response DTO for a single GR line (ADR-0011 D-12).
 */
public record GoodsReceiptLineDto(
        Long   id,
        String uid,
        Long   goodsReceiptId,
        Long   purchaseOrderLineId,
        short  lineNo,
        Long   productId,
        String productCode,
        String productName,
        Long   unitId,
        String unitName,
        BigDecimal receivedQty,
        BigDecimal qtyInBase,
        BigDecimal unitCostAmount,
        BigDecimal lineCostAmount,
        String     currency
) {
    public static GoodsReceiptLineDto from(GoodsReceiptLine l) {
        return new GoodsReceiptLineDto(
                l.getId(), l.getUid(),
                l.getGoodsReceipt().getId(),
                l.getPurchaseOrderLineId(),
                l.getLineNo(),
                l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(),
                l.getReceivedQty(), l.getQtyInBase(),
                l.getUnitCostAmount(), l.getLineCostAmount(),
                l.getCurrency());
    }
}
