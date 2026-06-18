package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.SupplierQuoteLine;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;

public record SupplierQuoteLineDto(
        Long   id,
        String uid,
        int    lineNo,
        Long   rfqLineId,
        String rfqLineUid,
        Long   productId,
        String productCode,
        String productName,
        Long   unitId,
        String unitName,
        BigDecimal quotedQty,
        BigDecimal quotedQtyInBase,
        BigDecimal unitPriceAmount,
        BigDecimal lineTotalAmount,
        String currency
) {
    public static SupplierQuoteLineDto from(SupplierQuoteLine l) {
        return new SupplierQuoteLineDto(
                l.getId(), l.getUid(), l.getLineNo(),
                l.getRfqLineId(), l.getRfqLineUid(),
                l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(),
                l.getQuotedQty(), l.getQuotedQtyInBase(),
                l.getUnitPriceAmount(), l.getLineTotalAmount(), CurrencyCode.value(l.getCurrency()));
    }
}
