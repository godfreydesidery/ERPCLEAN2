package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.PurchaseRequisitionLine;
import java.math.BigDecimal;
import java.time.LocalDate;

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
        LocalDate  requiredByDate,
        Long       suggestedSupplierId,
        String     convertedToPoLineUid,
        String note
) {
    public static PurchaseRequisitionLineDto from(PurchaseRequisitionLine l) {
        return new PurchaseRequisitionLineDto(
                l.getId(), l.getUid(), l.getLineNo(),
                l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(),
                l.getRequestedQty(), l.getEstimatedUnitCost(),
                l.getRequiredByDate(), l.getSuggestedSupplierId(), l.getConvertedToPoLineUid(),
                l.getNote());
    }
}
