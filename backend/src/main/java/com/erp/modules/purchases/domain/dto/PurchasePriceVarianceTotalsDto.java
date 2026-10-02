package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;

/**
 * Footer of Purchase Price Variance, in the company's base currency only. A positive variance
 * means the business paid MORE than the order price.
 *
 * @param billVarianceTotal   null when the caller may not see supplier bills
 * @param rowsInOtherCurrency variance lines in a foreign currency, left OUT of both totals
 */
public record PurchasePriceVarianceTotalsDto(
        long       lines,
        BigDecimal receiptVarianceTotal,
        BigDecimal billVarianceTotal,
        long       rowsInOtherCurrency
) {}
