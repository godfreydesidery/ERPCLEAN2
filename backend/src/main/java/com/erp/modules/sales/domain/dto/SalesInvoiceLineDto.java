package com.erp.modules.sales.domain.dto;

import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.sales.domain.entity.SalesInvoiceLine;
import java.math.BigDecimal;

/**
 * Response DTO for a SalesInvoiceLine (ADR-0008 D-12).
 * Snapshot fields (productCode, productName, unitName, listPrice, vatRate) are shown as stored.
 */
public record SalesInvoiceLineDto(
        Long id,
        String uid,
        Long invoiceId,
        Long companyId,
        Long branchId,
        short lineNo,
        Long productId,
        String productCode,
        String productName,
        Long unitId,
        String unitName,
        BigDecimal quantity,
        BigDecimal qtyInBase,
        /** What the caller entered before weighed-goods scale-step rounding; null if never rounded. */
        BigDecimal requestedQuantity,
        BigDecimal listPriceAmount,
        BigDecimal unitPriceAmount,
        boolean priceOverridden,
        Long overriddenBy,
        BigDecimal lineDiscountAmount,
        BigDecimal lineDiscountPercent,
        VatStatus vatStatus,
        BigDecimal vatRate,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        BigDecimal grossAmount,
        boolean priceInclusive,
        String currency,
        String createdAt,
        Long createdBy
) {

    public static SalesInvoiceLineDto from(SalesInvoiceLine l) {
        return new SalesInvoiceLineDto(
                l.getId(),
                l.getUid(),
                l.getInvoice().getId(),
                l.getCompanyId(),
                l.getBranchId(),
                l.getLineNo(),
                l.getProductId(),
                l.getProductCode(),
                l.getProductName(),
                l.getUnitId(),
                l.getUnitName(),
                l.getQuantity(),
                l.getQtyInBase(),
                l.getRequestedQuantity(),
                l.getListPriceAmount(),
                l.getUnitPriceAmount(),
                l.isPriceOverridden(),
                l.getOverriddenBy(),
                l.getLineDiscountAmount(),
                l.getLineDiscountPercent(),
                l.getVatStatus(),
                l.getVatRate(),
                l.getNetAmount(),
                l.getVatAmount(),
                l.getGrossAmount(),
                l.isPriceInclusive(),
                l.getCurrency().value(),
                l.getCreatedAt() != null ? l.getCreatedAt().toString() : null,
                l.getCreatedBy()
        );
    }
}
