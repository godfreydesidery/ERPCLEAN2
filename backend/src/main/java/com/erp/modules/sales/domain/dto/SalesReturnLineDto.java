package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.SalesReturnLine;
import java.math.BigDecimal;

/** Response DTO for a sales return line (ADR-0021 D-11, Stage 2). */
public record SalesReturnLineDto(
        Long id,
        String uid,
        Long salesReturnId,
        Long deliveryLineId,
        String deliveryLineUid,
        short lineNo,
        Long productId,
        String productCode,
        String productName,
        Long unitId,
        String unitName,
        BigDecimal qtyReturned,
        BigDecimal qtyReturnedBase,
        BigDecimal unitPriceAmount,
        BigDecimal lineDiscountAmount,
        BigDecimal lineDiscountPercent,
        String vatStatus,
        BigDecimal vatRate,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        BigDecimal grossAmount,
        String currency
) {
    public static SalesReturnLineDto from(SalesReturnLine l) {
        return new SalesReturnLineDto(
                l.getId(), l.getUid(),
                l.getSalesReturnId(), l.getDeliveryLineId(), l.getDeliveryLineUid(),
                l.getLineNo(),
                l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(),
                l.getQtyReturned(), l.getQtyReturnedBase(),
                l.getUnitPriceAmount(),
                l.getLineDiscountAmount(), l.getLineDiscountPercent(),
                l.getVatStatus() != null ? l.getVatStatus().name() : null, l.getVatRate(),
                l.getNetAmount(), l.getVatAmount(), l.getGrossAmount(),
                l.getCurrency().value());
    }
}
