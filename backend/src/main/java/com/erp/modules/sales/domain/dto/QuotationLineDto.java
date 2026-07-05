package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.QuotationLine;
import java.math.BigDecimal;

public record QuotationLineDto(
        Long id,
        String uid,
        short lineNo,
        Long productId,
        String productCode,
        String productName,
        Long unitId,
        String unitName,
        BigDecimal quantity,
        BigDecimal qtyInBase,
        BigDecimal listPriceAmount,
        BigDecimal unitPriceAmount,
        BigDecimal lineDiscountAmount,
        BigDecimal lineDiscountPercent,
        String vatStatus,
        BigDecimal vatRate,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        BigDecimal grossAmount,
        boolean priceInclusive,
        String currency
) {
    public static QuotationLineDto from(QuotationLine l) {
        return new QuotationLineDto(
                l.getId(), l.getUid(), l.getLineNo(),
                l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(),
                l.getQuantity(), l.getQtyInBase(),
                l.getListPriceAmount(), l.getUnitPriceAmount(),
                l.getLineDiscountAmount(), l.getLineDiscountPercent(),
                l.getVatStatus() != null ? l.getVatStatus().name() : null,
                l.getVatRate(),
                l.getNetAmount(), l.getVatAmount(), l.getGrossAmount(),
                l.isPriceInclusive(),
                l.getCurrency().value());
    }
}
