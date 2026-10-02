package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;

/**
 * Footer of Purchases by Supplier, in the company's base currency only. Columns the caller may not
 * see are null here exactly as they are on the rows.
 *
 * @param rowsInOtherCurrency supplier rows in a foreign currency, left OUT of every total
 */
public record PurchasesBySupplierTotalsDto(
        long       receipts,
        BigDecimal receivedValue,
        BigDecimal returnsValue,
        BigDecimal netPurchases,
        BigDecimal billedAmount,
        BigDecimal unpaidAmount,
        long       rowsInOtherCurrency
) {}
