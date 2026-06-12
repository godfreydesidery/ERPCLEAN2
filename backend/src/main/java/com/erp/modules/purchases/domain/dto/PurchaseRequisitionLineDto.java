package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.PurchaseRequisitionLine;
import java.math.BigDecimal;

public record PurchaseRequisitionLineDto(
        Long   id,
        String uid,
        int    lineNo,
        Long   productId,
        String productCode,
        String productName,
        Long   unitId,
        String unitName,
        BigDecimal requestedQty,
        BigDecimal estimatedUnitCost,
        String note
) {
    public static PurchaseRequisitionLineDto from(PurchaseRequisitionLine l) {
        return new PurchaseRequisitionLineDto(
                l.getId(), l.getUid(), l.getLineNo(),
                l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(),
                l.getRequestedQty(), l.getEstimatedUnitCost(), l.getNote());
    }
}
