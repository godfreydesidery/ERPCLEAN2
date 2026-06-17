package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.PurchaseReturnLine;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;

public record PurchaseReturnLineDto(
        Long   id,
        String uid,
        int    lineNo,
        Long   goodsReceiptLineId,
        String goodsReceiptLineUid,
        Long   productId,
        String productCode,
        String productName,
        Long   unitId,
        String unitName,
        BigDecimal returnedQty,
        BigDecimal returnedQtyInBase,
        BigDecimal unitCostAmount,
        BigDecimal lineValueAmount,
        String currency
) {
    public static PurchaseReturnLineDto from(PurchaseReturnLine l) {
        return new PurchaseReturnLineDto(
                l.getId(), l.getUid(), l.getLineNo(),
                l.getGoodsReceiptLineId(), l.getGoodsReceiptLineUid(),
                l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(),
                l.getReturnedQty(), l.getReturnedQtyInBase(),
                l.getUnitCostAmount(), l.getLineValueAmount(), CurrencyCode.value(l.getCurrency()));
    }
}
