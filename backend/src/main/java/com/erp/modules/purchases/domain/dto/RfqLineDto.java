package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.RfqLine;
import java.math.BigDecimal;

public record RfqLineDto(
        Long   id,
        String uid,
        int    lineNo,
        Long   productId,
        String productCode,
        String productName,
        Long   unitId,
        String unitName,
        BigDecimal quantity,
        BigDecimal quantityInBase
) {
    public static RfqLineDto from(RfqLine l) {
        return new RfqLineDto(
                l.getId(), l.getUid(), l.getLineNo(),
                l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(),
                l.getQuantity(), l.getQuantityInBase());
    }
}
