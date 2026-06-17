package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.SalesOrderLine;
import java.math.BigDecimal;

public record SalesOrderLineDto(
        Long id,
        String uid,
        short lineNo,
        Long productId,
        String productCode,
        String productName,
        Long unitId,
        String unitName,
        BigDecimal qtyOrdered,
        BigDecimal qtyOrderedBase,
        BigDecimal qtyFulfilledBase,
        BigDecimal qtyInvoicedBase,
        BigDecimal qtyReservedBase,
        BigDecimal openQtyBase,
        BigDecimal listPriceAmount,
        BigDecimal unitPriceAmount,
        boolean priceOverridden,
        BigDecimal lineDiscountAmount,
        BigDecimal lineDiscountPercent,
        String vatStatus,
        BigDecimal vatRate,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        BigDecimal grossAmount,
        String currency
) {
    public static SalesOrderLineDto from(SalesOrderLine l) {
        return new SalesOrderLineDto(
                l.getId(), l.getUid(), l.getLineNo(),
                l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(),
                l.getQtyOrdered(), l.getQtyOrderedBase(),
                l.getQtyFulfilledBase(), l.getQtyInvoicedBase(), l.getQtyReservedBase(),
                l.openQtyBase(),
                l.getListPriceAmount(), l.getUnitPriceAmount(),
                l.isPriceOverridden(),
                l.getLineDiscountAmount(), l.getLineDiscountPercent(),
                l.getVatStatus() != null ? l.getVatStatus().name() : null,
                l.getVatRate(),
                l.getNetAmount(), l.getVatAmount(), l.getGrossAmount(),
                l.getCurrency().value());
    }
}
