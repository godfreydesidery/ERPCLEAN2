package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.DeliveryLine;
import java.math.BigDecimal;

public record DeliveryLineDto(
        Long id,
        String uid,
        short lineNo,
        Long salesOrderLineId,
        String salesOrderLineUid,
        Long productId,
        String productCode,
        String productName,
        Long unitId,
        String unitName,
        BigDecimal qtyDelivered,
        BigDecimal qtyDeliveredBase,
        BigDecimal qtyInvoicedBase,
        BigDecimal returnedQtyBase,
        BigDecimal issueValueAmount,
        String currency
) {
    public static DeliveryLineDto from(DeliveryLine l) {
        return new DeliveryLineDto(
                l.getId(), l.getUid(), l.getLineNo(),
                l.getSalesOrderLineId(), l.getSalesOrderLineUid(),
                l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(),
                l.getQtyDelivered(), l.getQtyDeliveredBase(),
                l.getQtyInvoicedBase(), l.getReturnedQtyBase(),
                l.getIssueValueAmount(),
                l.getCurrency());
    }
}
