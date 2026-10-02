package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;

/**
 * Footer of Open Purchase Orders.
 *
 * @param orders              distinct open orders
 * @param lines               open order lines
 * @param outstandingValue    in the company's base currency
 * @param rowsInOtherCurrency lines in a foreign currency, left OUT of {@code outstandingValue}
 */
public record OpenPurchaseOrdersTotalsDto(
        long       orders,
        long       lines,
        BigDecimal outstandingValue,
        long       rowsInOtherCurrency
) {}
